package com.example.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Reads new messages from a Discord channel and relays them into Minecraft chat.
 *
 * <p>Uses the REST API on a fixed-delay schedule rather than the gateway, which
 * keeps the mod dependency-free. All network work happens on a single daemon
 * thread; anything touching game state is handed back to the server thread via
 * {@link MinecraftServer#execute(Runnable)}.</p>
 */
public final class DiscordMessagePoller
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String API_BASE = "https://discord.com/api/v10/channels/";
    private static final int MAX_MESSAGES_PER_POLL = 50;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    private static final long DEFAULT_BACKOFF_MILLIS = 15_000L;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;
    private static final String PLAYERS_COMMAND = "!players";

    private final AtomicReference<String> lastSeenId = new AtomicReference<>();
    private final AtomicBoolean halted = new AtomicBoolean(false);

    private volatile long backoffUntilMillis;
    private HttpClient client;
    private ScheduledExecutorService executor;
    private MinecraftServer server;

    /**
     * Starts polling, replacing any run already in progress. Safe to call when
     * the inbound relay is disabled or unconfigured: it simply does nothing.
     */
    public synchronized void start(final MinecraftServer minecraftServer)
    {
        if (minecraftServer == null)
        {
            return;
        }
        stopInternal();
        server = minecraftServer;
        halted.set(false);
        backoffUntilMillis = 0L;
        lastSeenId.set(null);

        if (!DiscordConfig.inboundEnabled)
        {
            return;
        }
        if (!DiscordConfig.isInboundConfigured())
        {
            LOGGER.warn("Discord inbound relay is enabled but needs a bot token and a channel id. Not polling.");
            return;
        }

        client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        executor = Executors.newSingleThreadScheduledExecutor(DiscordMessagePoller::newWorkerThread);

        final long interval = DiscordConfig.pollIntervalSeconds;
        executor.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.SECONDS);
        LOGGER.info("Discord inbound relay polling every {} seconds.", interval);
    }

    public synchronized void stop()
    {
        stopInternal();
        server = null;
    }

    /**
     * Applies configuration changes without needing a server restart.
     */
    public synchronized void restart()
    {
        final MinecraftServer current = server;
        if (current != null)
        {
            start(current);
        }
    }

    private synchronized void stopInternal()
    {
        if (executor == null)
        {
            client = null;
            return;
        }
        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS))
            {
                executor.shutdownNow();
            }
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        finally
        {
            executor = null;
            client = null;
        }
    }

    private static Thread newWorkerThread(final Runnable runnable)
    {
        final Thread thread = new Thread(runnable, "DiscordBridge-Poller");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * A throw escaping here would silently cancel the scheduled task, so nothing gets out.
     */
    private void pollSafely()
    {
        try
        {
            poll();
        }
        catch (final RuntimeException e)
        {
            LOGGER.error("Discord inbound poll failed unexpectedly", e);
            backOff(DEFAULT_BACKOFF_MILLIS);
        }
    }

    private void poll()
    {
        if (halted.get() || !DiscordConfig.inboundEnabled || !DiscordConfig.isInboundConfigured())
        {
            return;
        }
        if (System.currentTimeMillis() < backoffUntilMillis)
        {
            return;
        }

        final MinecraftServer target;
        final HttpClient httpClient;
        synchronized (this)
        {
            target = server;
            httpClient = client;
        }
        if (target == null || httpClient == null)
        {
            return;
        }

        final String after = lastSeenId.get();
        final boolean priming = after == null;

        final StringBuilder url = new StringBuilder(API_BASE)
                .append(DiscordConfig.channelId)
                .append("/messages?limit=")
                .append(priming ? 1 : MAX_MESSAGES_PER_POLL);
        if (!priming)
        {
            url.append("&after=").append(after);
        }

        final HttpResponse<String> response = request(httpClient, url.toString());
        if (response == null || !isUsable(response))
        {
            return;
        }

        final JsonArray messages = parseArray(response.body());
        if (messages == null || messages.isEmpty())
        {
            return;
        }

        // Discord returns newest first.
        final String newestId = idOf(messages.get(0));
        if (newestId != null)
        {
            lastSeenId.set(newestId);
        }
        if (priming)
        {
            // First run only records the head of the channel so a restart never
            // replays the backlog into chat.
            return;
        }

        final List<JsonObject> ordered = new ArrayList<>(messages.size());
        for (int i = messages.size() - 1; i >= 0; i--)
        {
            final JsonElement element = messages.get(i);
            if (element != null && element.isJsonObject())
            {
                ordered.add(element.getAsJsonObject());
            }
        }
        for (final JsonObject message : ordered)
        {
            process(target, message);
        }
    }

    private HttpResponse<String> request(final HttpClient httpClient, final String url)
    {
        try
        {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bot " + DiscordConfig.botToken)
                    .header("User-Agent", "DiscordBridge (Minecraft Forge mod)")
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (final IllegalArgumentException e)
        {
            LOGGER.error("The configured Discord channel id produced a malformed request URL.");
            halt();
            return null;
        }
        catch (final IOException e)
        {
            LOGGER.warn("Could not reach Discord while polling: {}", e.toString());
            backOff(DEFAULT_BACKOFF_MILLIS);
            return null;
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * @return true if the body is worth parsing. Never logs the token.
     */
    private boolean isUsable(final HttpResponse<String> response)
    {
        final int status = response.statusCode();
        if (status >= 200 && status < 300)
        {
            return true;
        }
        if (status == 401 || status == 403)
        {
            LOGGER.error("Discord rejected the bot credentials (HTTP {}). Inbound relay stopped. "
                    + "Check the token, that the bot has joined the server, and that it can view the channel.", status);
            halt();
            return false;
        }
        if (status == 404)
        {
            LOGGER.error("Discord could not find that channel (HTTP 404). Inbound relay stopped.");
            halt();
            return false;
        }
        if (status == 429)
        {
            backOff(retryAfterMillis(response.body()));
            LOGGER.warn("Discord rate limited the inbound poller. Backing off.");
            return false;
        }
        LOGGER.warn("Discord returned HTTP {} while polling. Backing off.", status);
        backOff(DEFAULT_BACKOFF_MILLIS);
        return false;
    }

    private void process(final MinecraftServer target, final JsonObject message)
    {
        if (!DiscordConfig.relayBotMessages && isAutomated(message))
        {
            // This is the loop guard: our own outbound webhook posts land here.
            return;
        }

        final String content = DiscordMessageFormatter.contentOf(message);
        if (content.isEmpty())
        {
            return;
        }

        if (PLAYERS_COMMAND.equals(content.trim().toLowerCase(Locale.ROOT)))
        {
            if (DiscordConfig.respondToPlayersCommand)
            {
                respondWithPlayerList(target);
            }
            return;
        }

        if (content.startsWith("/"))
        {
            // Broadcasts are display-only and cannot execute anything, but a
            // relayed message should never even look like a command.
            return;
        }

        final String author = DiscordMessageFormatter.authorName(message);
        final Component line = DiscordMessageFormatter.toChatComponent(author, content);
        target.execute(() -> target.getPlayerList().broadcastSystemMessage(line, false));
    }

    private void respondWithPlayerList(final MinecraftServer target)
    {
        target.execute(() -> {
            final List<ServerPlayer> players = target.getPlayerList().getPlayers();
            if (players.isEmpty())
            {
                DiscordBridge.sender().send("Nobody is online right now.");
                return;
            }
            final String names = players.stream()
                    .map(player -> player.getGameProfile().getName())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(", "));
            DiscordBridge.sender().send("**" + players.size() + "/" + target.getPlayerList().getMaxPlayers()
                    + " online:** " + names);
        });
    }

    private static boolean isAutomated(final JsonObject message)
    {
        if (message.has("webhook_id"))
        {
            return true;
        }
        final JsonElement author = message.get("author");
        if (author == null || !author.isJsonObject())
        {
            return false;
        }
        final JsonElement bot = author.getAsJsonObject().get("bot");
        return bot != null && bot.isJsonPrimitive() && bot.getAsBoolean();
    }

    private static String idOf(final JsonElement element)
    {
        if (element == null || !element.isJsonObject())
        {
            return null;
        }
        final JsonElement id = element.getAsJsonObject().get("id");
        return id == null || !id.isJsonPrimitive() ? null : id.getAsString();
    }

    private static JsonArray parseArray(final String body)
    {
        if (body == null || body.isBlank())
        {
            return null;
        }
        try
        {
            final JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        }
        catch (final JsonSyntaxException e)
        {
            LOGGER.warn("Discord returned a response that could not be parsed as JSON.");
            return null;
        }
    }

    private static long retryAfterMillis(final String body)
    {
        try
        {
            final JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (parsed.isJsonObject())
            {
                final JsonElement retryAfter = parsed.getAsJsonObject().get("retry_after");
                if (retryAfter != null && retryAfter.isJsonPrimitive())
                {
                    return (long) (retryAfter.getAsDouble() * 1000.0D);
                }
            }
        }
        catch (final JsonSyntaxException | NumberFormatException e)
        {
            LOGGER.debug("Could not read retry_after from the rate limit response.");
        }
        return DEFAULT_BACKOFF_MILLIS;
    }

    private void backOff(final long millis)
    {
        final long clamped = Math.min(Math.max(millis, 0L), MAX_BACKOFF_MILLIS);
        backoffUntilMillis = System.currentTimeMillis() + clamped;
    }

    private void halt()
    {
        // Stops the polling work without tearing down the executor from inside
        // one of its own tasks. Re-enable with /discordbridge inbound enabled true.
        halted.set(true);
    }
}
