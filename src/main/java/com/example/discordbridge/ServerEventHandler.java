package com.example.discordbridge;

import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
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
        final String name = player.getGameProfile().getName();
        final MinecraftServer server = player.getServer();
        final int current = server != null ? server.getPlayerList().getPlayerCount() : 1;
        final int max = server != null ? server.getPlayerList().getMaxPlayers() : 20;

        if (DiscordConfig.useRichEmbeds)
        {
            final JsonObject embed = new JsonObject();
            embed.addProperty("description", "**" + name + "** joined the server");
            embed.addProperty("color", 0x57F287); // Green
            embed.addProperty("timestamp", Instant.now().toString());

            final JsonObject author = new JsonObject();
            author.addProperty("name", name);
            author.addProperty("icon_url", "https://mc-heads.net/avatar/" + player.getStringUUID() + "/100.png");
            embed.add("author", author);

            final JsonObject footer = new JsonObject();
            footer.addProperty("text", "Online: " + current + " / " + max);
            embed.add("footer", footer);

            DiscordBridge.sender().sendEmbed(embed);
        }
        else
        {
            DiscordBridge.sender().send("**" + name + "** joined the server (" + current + "/" + max + ")");
        }
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
        final String name = player.getGameProfile().getName();
        final MinecraftServer server = player.getServer();
        final int playerCount = server != null ? server.getPlayerList().getPlayerCount() : 0;
        final int remaining = (server != null && server.getPlayerList().getPlayers().contains(player))
                ? Math.max(0, playerCount - 1)
                : playerCount;
        final int max = server != null ? server.getPlayerList().getMaxPlayers() : 20;

        if (DiscordConfig.useRichEmbeds)
        {
            final JsonObject embed = new JsonObject();
            embed.addProperty("description", "**" + name + "** left the server");
            embed.addProperty("color", 0xE67E22); // Orange
            embed.addProperty("timestamp", Instant.now().toString());

            final JsonObject author = new JsonObject();
            author.addProperty("name", name);
            author.addProperty("icon_url", "https://mc-heads.net/avatar/" + player.getStringUUID() + "/100.png");
            embed.add("author", author);

            final JsonObject footer = new JsonObject();
            footer.addProperty("text", "Online: " + remaining + " / " + max);
            embed.add("footer", footer);

            DiscordBridge.sender().sendEmbed(embed);
        }
        else
        {
            DiscordBridge.sender().send("**" + name + "** left the server (" + remaining + "/" + max + ")");
        }
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
        DiscordBridge.sender().sendChat(text, player);
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
        final String rawMessage = player.getCombatTracker().getDeathMessage().getString();
        final String message = rawMessage.isBlank()
                ? player.getGameProfile().getName() + " died"
                : rawMessage;

        if (DiscordConfig.useRichEmbeds)
        {
            final JsonObject embed = new JsonObject();
            embed.addProperty("description", ":skull: " + message);
            embed.addProperty("color", 0x992D22); // Dark Red
            embed.addProperty("timestamp", Instant.now().toString());

            final JsonObject author = new JsonObject();
            author.addProperty("name", player.getGameProfile().getName());
            author.addProperty("icon_url", "https://mc-heads.net/avatar/" + player.getStringUUID() + "/100.png");
            embed.add("author", author);

            DiscordBridge.sender().sendEmbed(embed);
        }
        else
        {
            DiscordBridge.sender().send(message);
        }
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

        final String actionText;
        if (frameType == FrameType.CHALLENGE)
        {
            actionText = "has completed the challenge";
        }
        else if (frameType == FrameType.GOAL)
        {
            actionText = "has reached the goal";
        }
        else
        {
            actionText = "has made the advancement";
        }

        if (DiscordConfig.useRichEmbeds)
        {
            final JsonObject embed = new JsonObject();
            embed.addProperty("title", title);
            embed.addProperty("description", "**" + playerName + "** " + actionText + " **[" + title + "]**\n*" + display.getDescription().getString() + "*");
            embed.addProperty("color", frameType == FrameType.CHALLENGE ? 0x9B59B6 : 0xF1C40F);
            embed.addProperty("timestamp", Instant.now().toString());

            final JsonObject author = new JsonObject();
            author.addProperty("name", playerName);
            author.addProperty("icon_url", "https://mc-heads.net/avatar/" + player.getStringUUID() + "/100.png");
            embed.add("author", author);

            DiscordBridge.sender().sendEmbed(embed);
        }
        else
        {
            DiscordBridge.sender().send("**" + playerName + "** " + actionText + " **[" + title + "]**");
        }
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
