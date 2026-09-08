package com.example.discordbridge;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
import java.util.Set;

/**
 * Translates the four game events into webhook messages.
 *
 * <p>Every handler bails out early if the feature is disabled or the entity is
 * not a real server player, so the common case costs a couple of field reads.</p>
 */
@Mod.EventBusSubscriber(modid = DiscordBridge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEventHandler
{
    /**
     * Commands that are never broadcast in game nor relayed to Discord.
     *
     * <p>These are read-only informational commands that players run often; relaying
     * them would drown out real chat in both places.</p>
     */
    private static final Set<String> SILENT_COMMANDS = Set.of("hexparse", "players", "status", "stats");

    private ServerEventHandler()
    {
    }

    /**
     * @param normalized a lowercased command string with any leading slash removed
     * @return true if the command's root token is in {@link #SILENT_COMMANDS}
     */
    private static boolean isSilentCommand(final String normalized)
    {
        final int space = normalized.indexOf(' ');
        final String root = space < 0 ? normalized : normalized.substring(0, space);
        return SILENT_COMMANDS.contains(root);
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
            author.addProperty("icon_url", DiscordWebhookSender.getPlayerAvatarUrl(player));
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
        // Vanilla writes the player's stats on logout, so the cached snapshot is
        // now stale regardless of whether we relay the leave message.
        PlayerStatsService.invalidate();

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
            author.addProperty("icon_url", DiscordWebhookSender.getPlayerAvatarUrl(player));
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

        event.setCanceled(true);

        final boolean op = DiscordWebhookSender.isOp(player);
        // Resolved mentions are highlighted so the player sees the ping landed.
        final Component body = Component.literal(": ").withStyle(ChatFormatting.WHITE)
                .append(MentionResolver.decorate(text, ChatFormatting.WHITE));
        final Component line;
        if (op)
        {
            line = Component.literal("[ADMIN] ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(player.getGameProfile().getName()).withStyle(ChatFormatting.RED))
                    .append(body);
        }
        else
        {
            line = Component.literal(player.getGameProfile().getName()).withStyle(ChatFormatting.WHITE)
                    .append(body);
        }

        final MinecraftServer server = player.getServer();
        if (server != null)
        {
            server.getPlayerList().broadcastSystemMessage(line, false);
        }

        if (DiscordConfig.announceChat)
        {
            DiscordBridge.sender().sendChat(text, player);
        }
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
            author.addProperty("icon_url", DiscordWebhookSender.getPlayerAvatarUrl(player));
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
            author.addProperty("icon_url", DiscordWebhookSender.getPlayerAvatarUrl(player));
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
        final String rawCommand = event.getParseResults().getReader().getString();
        if (rawCommand == null || rawCommand.isBlank())
        {
            return;
        }

        final String trimmed = rawCommand.trim();
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        final String normalized = lower.startsWith("/") ? lower.substring(1) : lower;
        if (isSilentCommand(normalized))
        {
            return;
        }

        final CommandSourceStack source = event.getParseResults().getContext().getSource();
        final String safeCommand = redactSensitiveCommand(trimmed);

        final String senderName;
        if (source.getEntity() instanceof final ServerPlayer player)
        {
            senderName = player.getGameProfile().getName();
        }
        else
        {
            senderName = source.getTextName();
        }

        final MinecraftServer server = source.getServer();
        if (server != null)
        {
            final Component broadcast = Component.literal("[" + senderName + " : Executed " + safeCommand + " command]")
                    .withStyle(ChatFormatting.GRAY);
            server.getPlayerList().broadcastSystemMessage(broadcast, false);
        }

        if (!DiscordConfig.announceCommands)
        {
            return;
        }

        final String discordMessage = "[Executed " + safeCommand + " command]";
        if (source.getEntity() instanceof final ServerPlayer player)
        {
            DiscordBridge.sender().sendPlayerCommand(discordMessage, player);
        }
        else
        {
            DiscordBridge.sender().send("[" + senderName + " : Executed " + safeCommand + " command]");
        }
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
