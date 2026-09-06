package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.time.Duration;

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
            jda = JDABuilder.createLight(DiscordConfig.botToken,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .addEventListeners(new DiscordEventListener(server))
                    .build();

            LOGGER.info("Connecting Discord bot via JDA Gateway...");
        }
        catch (final Exception e)
        {
            LOGGER.error("Failed to initialize Discord JDA client: {}", e.getMessage());
            jda = null;
        }
    }

    public synchronized void stop()
    {
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
