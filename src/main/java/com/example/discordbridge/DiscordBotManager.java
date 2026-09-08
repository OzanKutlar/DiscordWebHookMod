package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages the JDA Discord bot lifecycle (WebSocket gateway connection).
 */
public final class DiscordBotManager
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private JDA jda;
    private MinecraftServer server;

    public synchronized void start(final MinecraftServer minecraftServer)
    {
        if (minecraftServer == null)
        {
            return;
        }
        stop();
        server = minecraftServer;

        if (!DiscordConfig.inboundEnabled)
        {
            LOGGER.info("Discord bot inbound is disabled.");
            return;
        }
        if (!DiscordConfig.isInboundConfigured())
        {
            LOGGER.warn("Discord bot is enabled but requires both a bot token and channel ID.");
            return;
        }

        if (connect(DiscordConfig.allowMentions))
        {
            return;
        }

        // Mentions are an optional extra; a chat bridge that is up without them
        // beats one that is down with them. Retry once in the plain
        // configuration rather than leaving the server with no relay at all.
        if (DiscordConfig.allowMentions)
        {
            LOGGER.warn("Retrying the Discord connection with mentions disabled for this session.");
            if (connect(false))
            {
                LOGGER.warn("Connected without mentions. The config setting is unchanged, so fix the cause above");
                LOGGER.warn("and run 'discordbridge mentions allow true' to try again.");
            }
        }
    }

    /**
     * Builds and starts a JDA instance.
     *
     * @param withMentions whether to request the member cache needed for @name resolution
     * @return true if the client was constructed, false if it failed
     */
    private boolean connect(final boolean withMentions)
    {
        try
        {
            final JDABuilder builder = JDABuilder.createLight(DiscordConfig.botToken, intents(withMentions))
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .addEventListeners(new DiscordEventListener(server));

            if (withMentions)
            {
                // Resolving @name needs the member list cached up front; without
                // this the directory would be empty and nothing would ever match.
                // No CacheFlag is required for this, and adding one here risks
                // pulling in an intent we do not request.
                builder.setMemberCachePolicy(MemberCachePolicy.ALL)
                        .setChunkingFilter(ChunkingFilter.ALL);
            }

            jda = builder.build();
            LOGGER.info("Connecting Discord bot via JDA Gateway (mentions={})...", withMentions);
            return true;
        }
        catch (final Exception e)
        {
            handleStartupFailure(e, withMentions);
            jda = null;
            return false;
        }
    }

    /**
     * The Server Members Intent is privileged, so it is only requested when the
     * feature that needs it is actually switched on.
     */
    private static List<GatewayIntent> intents(final boolean withMentions)
    {
        final List<GatewayIntent> intents = new ArrayList<>();
        intents.add(GatewayIntent.GUILD_MESSAGES);
        intents.add(GatewayIntent.MESSAGE_CONTENT);
        if (withMentions)
        {
            intents.add(GatewayIntent.GUILD_MEMBERS);
        }
        return intents;
    }

    /**
     * A missing or mismatched privileged intent is by far the most likely
     * startup failure, so it gets an explicit instruction naming the intent
     * rather than a raw stack trace.
     */
    private static void handleStartupFailure(final Exception e, final boolean withMentions)
    {
        final String raw = e.getMessage() == null ? "" : e.getMessage();
        final String message = raw.toLowerCase(Locale.ROOT);

        if (message.contains("disallowed intents"))
        {
            LOGGER.error("Discord refused the connection because a privileged intent is not enabled.");
            LOGGER.error("Enable it at Developer Portal > your application > Bot > Privileged Gateway Intents.");
            if (withMentions)
            {
                LOGGER.error("Mentions need the Server Members Intent. To turn them off instead, run:");
                LOGGER.error("  discordbridge mentions allow false");
            }
            return;
        }
        if (message.contains("cacheflag") && message.contains("gatewayintent"))
        {
            LOGGER.error("JDA rejected the cache configuration: {}", raw);
            LOGGER.error("This is a bug in the mod rather than a setting on your Discord application.");
            return;
        }
        LOGGER.error("Failed to initialize Discord JDA client: {}", raw);
    }

    public synchronized void stop()
    {
        MentionResolver.clear();
        if (jda != null)
        {
            try
            {
                LOGGER.info("Shutting down Discord bot gateway...");
                jda.shutdown();
                if (!jda.awaitShutdown(SHUTDOWN_TIMEOUT))
                {
                    jda.shutdownNow();
                }
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                jda.shutdownNow();
            }
            finally
            {
                jda = null;
            }
        }
        server = null;
    }

    public synchronized void restart()
    {
        final MinecraftServer current = server;
        if (current != null)
        {
            start(current);
        }
    }

    public synchronized JDA getJda()
    {
        return jda;
    }

    public synchronized String getStatusText()
    {
        if (!DiscordConfig.inboundEnabled)
        {
            return "disabled";
        }
        if (!DiscordConfig.isInboundConfigured())
        {
            return "not configured (missing token or channel)";
        }
        if (jda == null)
        {
            return "stopped";
        }
        return jda.getStatus().name();
    }
}
