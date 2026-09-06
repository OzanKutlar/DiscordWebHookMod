package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for the Discord Bridge mod.
 *
 * <p>The mod is entirely server-side: it registers no blocks, items or network
 * channels, so vanilla clients can connect without installing anything.</p>
 */
@Mod(DiscordBridge.MODID)
public class DiscordBridge
{
    public static final String MODID = "discordbridge";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DiscordWebhookSender SENDER = new DiscordWebhookSender();

    public DiscordBridge(FMLJavaModLoadingContext context)
    {
        // COMMON (not SERVER) on purpose: Forge syncs SERVER configs to every
        // connecting client, which would hand the webhook URL to all players.
        context.registerConfig(ModConfig.Type.COMMON, DiscordConfig.SPEC, MODID + "-common.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * @return the shared sender. Never null, but may be idle if the server has not started yet.
     */
    public static DiscordWebhookSender sender()
    {
        return SENDER;
    }

    @SubscribeEvent
    public void onServerStarted(final ServerStartedEvent event)
    {
        SENDER.start();
        LOGGER.info("Discord Bridge ready (enabled={}, webhook configured={})",
                DiscordConfig.enabled, !DiscordConfig.webhookUrl.isBlank());
    }

    @SubscribeEvent
    public void onServerStopping(final ServerStoppingEvent event)
    {
        SENDER.stop();
    }
}
