package com.example.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.time.Instant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Posts messages to a Discord webhook off the server thread.
 *
 * <p>All sends go through a single daemon worker fed by a bounded queue. If
 * Discord is slow or unreachable the queue fills and further messages are
 * dropped with a warning, so the server tick is never blocked and memory can
 * never grow without bound.</p>
 */
public final class DiscordWebhookSender
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_CONTENT_LENGTH = 1900;
    private static final String MARKDOWN_SPECIALS = "*_~`|>\\";
    private static final char SECTION_SIGN = '\u00A7';
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private HttpClient client;
    private ThreadPoolExecutor executor;

    public synchronized void start()
    {
        if (executor != null && !executor.isShutdown())
        {
            return;
        }
        client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                DiscordWebhookSender::newWorkerThread,
                (task, pool) -> LOGGER.warn("Discord webhook queue is full, dropping a message."));
    }

    public synchronized void stop()
    {
        if (executor == null)
        {
            return;
        }
        executor.shutdown();
        try
        {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS))
            {
                LOGGER.warn("Discord webhook worker did not finish in time, forcing shutdown.");
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
        final Thread thread = new Thread(runnable, "DiscordBridge-Webhook");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Fire and forget. Failures are logged, never surfaced to players.
     */
    public void send(final String rawContent)
    {
        dispatch(rawContent, null, null, null, null);
    }

    /**
     * Send and report the outcome.
     */
    public void sendWithResult(final String rawContent, final Consumer<String> callback)
    {
        dispatch(rawContent, null, null, null, callback);
    }

    /**
     * Returns the MC-Heads isometric 3D head avatar URL for a player.
     */
    public static String getPlayerAvatarUrl(final ServerPlayer player)
    {
        return "https://mc-heads.net/head/" + player.getGameProfile().getName();
    }

    /**
     * Returns true if the player has operator permissions.
     */
    public static boolean isOp(final ServerPlayer player)
    {
        if (player == null)
        {
            return false;
        }
        final MinecraftServer server = player.getServer();
        if (server != null && server.getPlayerList().isOp(player.getGameProfile()))
        {
            return true;
        }
        return player.hasPermissions(2);
    }

    /**
     * Relays an in-game player chat message with their specific username and skin head avatar.
     */
    public void sendChat(final String rawContent, final ServerPlayer player)
    {
        final boolean op = isOp(player);
        final String username = (op ? "[ADMIN] " : "") + player.getGameProfile().getName();
        final String avatarUrl = getPlayerAvatarUrl(player);
        dispatch(rawContent, username, avatarUrl, null, null);
    }

    /**
     * Relays an in-game player command with their specific username and skin head avatar.
     */
    public void sendPlayerCommand(final String command, final ServerPlayer player)
    {
        final boolean op = isOp(player);
        final String username = (op ? "[ADMIN] " : "") + player.getGameProfile().getName();
        final String avatarUrl = getPlayerAvatarUrl(player);
        dispatch(command, username, avatarUrl, null, null);
    }

    /**
     * Sends a rich Discord embed using the default server webhook profile.
     */
    public void sendEmbed(final JsonObject embed)
    {
        dispatch(null, null, null, embed, null);
    }

    /**
     * Sends a rich Discord embed with custom username and avatar overrides.
     */
    public void sendEmbed(final JsonObject embed, final String username, final String avatarUrl)
    {
        dispatch(null, username, avatarUrl, embed, null);
    }

    public void sendServerStarted(final MinecraftServer server)
    {
        if (!DiscordConfig.announceServerStart)
        {
            return;
        }
        if (!DiscordConfig.useRichEmbeds)
        {
            send(":white_check_mark: **Server has started!** (Minecraft " + server.getServerVersion() + ")");
            return;
        }
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", "Server Online");
        embed.addProperty("description", "The Minecraft server is online and ready to accept connections.");
        embed.addProperty("color", 0x57F287); // Green

        final JsonArray fields = new JsonArray();
        final JsonObject versionField = new JsonObject();
        versionField.addProperty("name", "Version");
        versionField.addProperty("value", "Minecraft " + server.getServerVersion());
        versionField.addProperty("inline", true);
        fields.add(versionField);

        final JsonObject playersField = new JsonObject();
        playersField.addProperty("name", "Players");
        playersField.addProperty("value", "0 / " + server.getPlayerList().getMaxPlayers());
        playersField.addProperty("inline", true);
        fields.add(playersField);

        embed.add("fields", fields);
        embed.addProperty("timestamp", Instant.now().toString());

        final JsonObject footer = new JsonObject();
        footer.addProperty("text", DiscordConfig.username.isBlank() ? "Minecraft Server" : DiscordConfig.username);
        if (!DiscordConfig.avatarUrl.isBlank())
        {
            footer.addProperty("icon_url", DiscordConfig.avatarUrl);
        }
        embed.add("footer", footer);

        sendEmbed(embed);
    }

    public void sendServerStopping(final MinecraftServer server)
    {
        if (!DiscordConfig.announceServerStop)
        {
            return;
        }
        if (!DiscordConfig.useRichEmbeds)
        {
            send(":octagonal_sign: **Server is shutting down!**");
            return;
        }
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", "Server Offline");
        embed.addProperty("description", "The server is shutting down.");
        embed.addProperty("color", 0xED4245); // Red
        embed.addProperty("timestamp", Instant.now().toString());

        final JsonObject footer = new JsonObject();
        footer.addProperty("text", DiscordConfig.username.isBlank() ? "Minecraft Server" : DiscordConfig.username);
        if (!DiscordConfig.avatarUrl.isBlank())
        {
            footer.addProperty("icon_url", DiscordConfig.avatarUrl);
        }
        embed.add("footer", footer);

        sendEmbed(embed);
    }

    private void dispatch(final String rawContent, final String username, final String avatarUrl,
                          final JsonObject embed, final Consumer<String> callback)
    {
        if ((rawContent == null || rawContent.isBlank()) && embed == null)
        {
            report(callback, "Nothing to send.");
            return;
        }
        if (!DiscordConfig.enabled)
        {
            report(callback, "Discord Bridge is disabled. Enable it with: /discordbridge enabled true");
            return;
        }
        final String url = DiscordConfig.webhookUrl;
        if (url.isBlank())
        {
            report(callback, "No webhook URL is configured. Set one with: /discordbridge url <url>");
            return;
        }

        final ThreadPoolExecutor worker;
        final HttpClient httpClient;
        synchronized (this)
        {
            worker = executor;
            httpClient = client;
        }
        if (worker == null || httpClient == null || worker.isShutdown())
        {
            report(callback, "The webhook sender is not running.");
            return;
        }

        final String preparedContent = (rawContent != null && !rawContent.isBlank())
                ? prepareContent(rawContent)
                : null;
        final String payload = buildPayload(preparedContent, username, avatarUrl, embed);
        try
        {
            worker.execute(() -> post(httpClient, url, payload, callback));
        }
        catch (final RejectedExecutionException e)
        {
            report(callback, "The webhook queue rejected the message.");
        }
    }

    private void post(final HttpClient httpClient, final String url, final String payload,
                      final Consumer<String> callback)
    {
        try
        {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordBridge (Minecraft Forge mod)")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final int status = response.statusCode();
            if (status >= 200 && status < 300)
            {
                report(callback, null);
                return;
            }
            // Never log the URL itself, it is a secret.
            LOGGER.warn("Discord webhook rejected the message: HTTP {} {}", status, response.body());
            report(callback, "Discord returned HTTP " + status + ".");
        }
        catch (final IllegalArgumentException e)
        {
            LOGGER.error("The configured webhook URL is malformed.", e);
            report(callback, "The configured webhook URL is malformed.");
        }
        catch (final IOException e)
        {
            LOGGER.error("Failed to reach the Discord webhook: {}", e.toString());
            report(callback, "Could not reach Discord: " + e.getClass().getSimpleName());
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            report(callback, "The request was interrupted.");
        }
    }

    private static void report(final Consumer<String> callback, final String failure)
    {
        if (callback == null)
        {
            if (failure != null)
            {
                LOGGER.warn("Discord Bridge: {}", failure);
            }
            return;
        }
        try
        {
            callback.accept(failure);
        }
        catch (final RuntimeException e)
        {
            LOGGER.error("Discord Bridge result callback threw", e);
        }
    }

    private static String buildPayload(final String content, final String username,
                                       final String avatarUrl, final JsonObject embed)
    {
        final JsonObject json = new JsonObject();
        if (content != null && !content.isBlank())
        {
            json.addProperty("content", content);
        }
        if (username != null && !username.isBlank())
        {
            json.addProperty("username", username);
        }
        else if (!DiscordConfig.username.isBlank())
        {
            json.addProperty("username", DiscordConfig.username);
        }

        if (avatarUrl != null && !avatarUrl.isBlank())
        {
            json.addProperty("avatar_url", avatarUrl);
        }
        else if (!DiscordConfig.avatarUrl.isBlank())
        {
            json.addProperty("avatar_url", DiscordConfig.avatarUrl);
        }

        if (embed != null)
        {
            final JsonArray embeds = new JsonArray();
            embeds.add(embed);
            json.add("embeds", embeds);
        }

        if (DiscordConfig.suppressMentions)
        {
            final JsonObject allowed = new JsonObject();
            allowed.add("parse", new JsonArray());
            json.add("allowed_mentions", allowed);
        }
        return json.toString();
    }

    /**
     * Always strips colour codes and enforces Discord's length limit. Markdown
     * escaping and mention defusing are opt-in via config.
     */
    private static String prepareContent(final String raw)
    {
        String text = stripColourCodes(raw);
        if (DiscordConfig.sanitizeMarkdown)
        {
            text = escapeMarkdown(text);
        }
        if (DiscordConfig.suppressMentions)
        {
            text = defuseMentions(text);
        }
        if (text.length() > MAX_CONTENT_LENGTH)
        {
            text = text.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
        }
        return text;
    }

    private static String stripColourCodes(final String input)
    {
        if (input.indexOf(SECTION_SIGN) < 0)
        {
            return input;
        }
        final StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++)
        {
            if (input.charAt(i) == SECTION_SIGN)
            {
                i++; // also skip the formatting character that follows
                continue;
            }
            out.append(input.charAt(i));
        }
        return out.toString();
    }

    private static String escapeMarkdown(final String input)
    {
        final StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++)
        {
            final char c = input.charAt(i);
            if (MARKDOWN_SPECIALS.indexOf(c) >= 0)
            {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String defuseMentions(final String input)
    {
        return input.replace("@everyone", "[everyone]").replace("@here", "[here]");
    }
}
