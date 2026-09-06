package com.example.discordbridge;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

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

    @SubscribeEvent
    public static void onAdvancement(final AdvancementEvent.AdvancementEarnEvent event)
    {
        if (!DiscordConfig.announceAdvancements)
        {
            return;
        }
        if (!(event.getEntity() instanceof final ServerPlayer player))
        {
            return;
        }
        final Advancement advancement = event.getAdvancement();
        final DisplayInfo display = advancement.getDisplay();
        // Recipes and technical advancements do not have display info or announce to chat
        if (display == null || !display.shouldAnnounceChat())
        {
            return;
        }
        final FrameType frameType = display.getFrame();
        final String title = display.getTitle().getString();
        final String playerName = player.getGameProfile().getName();

        final String description;
        if (frameType == FrameType.CHALLENGE)
        {
            description = "**" + playerName + "** has completed the challenge **[" + title + "]**";
        }
        else if (frameType == FrameType.GOAL)
        {
            description = "**" + playerName + "** has reached the goal **[" + title + "]**";
        }
        else
        {
            description = "**" + playerName + "** has made the advancement **[" + title + "]**";
        }
        DiscordBridge.sender().send(description);
    }

    @SubscribeEvent
    public static void onCommand(final CommandEvent event)
    {
        if (!DiscordConfig.announceCommands)
        {
            return;
        }
        final CommandSourceStack source = event.getParseResults().getContext().getSource();
        final String rawCommand = event.getParseResults().getReader().getString();
        if (rawCommand == null || rawCommand.isBlank())
        {
            return;
        }

        final String senderName;
        if (source.getEntity() instanceof final ServerPlayer player)
        {
            senderName = player.getGameProfile().getName();
        }
        else
        {
            senderName = source.getTextName(); // e.g., "Server", "Rcon", etc.
        }

        final String safeCommand = redactSensitiveCommand(rawCommand.trim());
        DiscordBridge.sender().send("**" + senderName + "** executed: `" + safeCommand + "`");
    }

    /**
     * Redacts secrets if someone executes discordbridge commands that set the token or url.
     */
    private static String redactSensitiveCommand(final String cmd)
    {
        final String lower = cmd.toLowerCase(Locale.ROOT);
        final String normalized = lower.startsWith("/") ? lower.substring(1) : lower;

        if (normalized.startsWith("discordbridge url "))
        {
            return "/discordbridge url [REDACTED]";
        }
        if (normalized.startsWith("discordbridge inbound token "))
        {
            return "/discordbridge inbound token [REDACTED]";
        }
        return cmd.startsWith("/") ? cmd : "/" + cmd;
    }
}
