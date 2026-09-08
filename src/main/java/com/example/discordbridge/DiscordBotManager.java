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

        try
        {
            final JDABuilder builder = JDABuilder.createLight(DiscordConfig.botToken, intents())
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .addEventListeners(new DiscordEventListener(server));

            if (DiscordConfig.allowMentions)
            {
                // Resolving @name needs the member list cached up front; without
                // this the directory would be empty and nothing would ever match.
                builder.setMemberCachePolicy(MemberCachePolicy.ALL)
                        .setChunkingFilter(ChunkingFilter.ALL)
                        .enableCache(net.dv8tion.jda.api.utils.cache.CacheFlag.CLIENT_STATUS);
            }

            jda = builder.build();
            LOGGER.info("Connecting Discord bot via JDA Gateway (mentions={})...", DiscordConfig.allowMentions);
        }
        catch (final Exception e)
        {
            handleStartupFailure(e);
            jda = null;
        }
    }

    /**
     * The Server Members Intent is privileged, so it is only requested when the
     * feature that needs it is actually switched on.
     */
    private static List<GatewayIntent> intents()
    {
        final List<GatewayIntent> intents = new ArrayList<>();
        intents.add(GatewayIntent.GUILD_MESSAGES);
        intents.add(GatewayIntent.MESSAGE_CONTENT);
        if (DiscordConfig.allowMentions)
        {
            intents.add(GatewayIntent.GUILD_MEMBERS);
        }
        return intents;
    }

    /**
     * A missing privileged intent is by far the most likely startup failure once
     * mentions are enabled, so it gets an explicit instruction rather than a
     * raw stack trace.
     */
    private static void handleStartupFailure(final Exception e)
    {
        final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (DiscordConfig.allowMentions && message.contains("disallowed intents"))
        {
            LOGGER.error("Discord refused the connection because the Server Members Intent is not enabled.");
            LOGGER.error("Enable it at Developer Portal > your application > Bot > Privileged Gateway Intents,");
            LOGGER.error("or turn mentions off again with: discordbridge mentions allow false");
            return;
        }
        LOGGER.error("Failed to initialize Discord JDA client: {}", e.getMessage());
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
