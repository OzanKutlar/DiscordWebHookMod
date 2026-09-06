package com.example.discordbridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Translates the four game events into webhook messages.
 *
 * <p>Every handler bails out early if the feature is disabled or the entity is
 * not a real server player, so the common case costs a couple of field reads.</p>
 */
@Mod.EventBusSubscriber(modid = DiscordBridge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEventHandler
{
    private ServerEventHandler()
    {
    }

    @SubscribeEvent
    public static void onPlayerJoin(final PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!DiscordConfig.announceJoin)
        {
            return;
        }
        if (!(event.getEntity() instanceof final ServerPlayer player))
        {
            return;
        }
        DiscordBridge.sender().send("**" + player.getGameProfile().getName() + "** joined the server");
    }

    @SubscribeEvent
    public static void onPlayerLeave(final PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (!DiscordConfig.announceLeave)
        {
            return;
        }
        if (!(event.getEntity() instanceof final ServerPlayer player))
        {
            return;
        }
        DiscordBridge.sender().send("**" + player.getGameProfile().getName() + "** left the server");
    }

    @SubscribeEvent
    public static void onChat(final ServerChatEvent event)
    {
        if (!DiscordConfig.announceChat)
        {
            return;
        }
        final ServerPlayer player = event.getPlayer();
        if (player == null)
        {
            return;
        }
        final String text = event.getRawText();
        if (text == null || text.isBlank())
        {
            return;
        }
        DiscordBridge.sender().send("**" + player.getGameProfile().getName() + "**: " + text);
    }

    @SubscribeEvent
    public static void onDeath(final LivingDeathEvent event)
    {
        if (!DiscordConfig.announceDeath)
        {
            return;
        }
        if (!(event.getEntity() instanceof final ServerPlayer player))
        {
            return;
        }
        if (player.level().isClientSide())
        {
            return;
        }
        final String message = player.getCombatTracker().getDeathMessage().getString();
        DiscordBridge.sender().send(message.isBlank()
                ? player.getGameProfile().getName() + " died"
                : message);
    }
}
