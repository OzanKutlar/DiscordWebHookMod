package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles incoming Discord gateway events (messages and slash commands).
 */
public final class DiscordEventListener extends ListenerAdapter
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final char SECTION_SIGN = '\u00A7';
    private static final String PLAYERS_COMMAND = "players";
    private static final String CLEAR_CHAT_COMMAND = "clearchat";
    private static final String STATS_COMMAND = "stats";
    private static final String STATUS_COMMAND = "status";
    private static final String RESTART_COMMAND = "restart";
    private static final String CMD_COMMAND = "cmd";
    private static final String BTN_CONFIRM_PREFIX = "clearchat:confirm:";
    private static final String BTN_CANCEL_PREFIX = "clearchat:cancel:";

    private final MinecraftServer server;

    public DiscordEventListener(final MinecraftServer server)
    {
        this.server = server;
    }

    @Override
    public void onReady(final ReadyEvent event)
    {
        LOGGER.info("Discord bot connected as {}", event.getJDA().getSelfUser().getAsTag());

        // Register slash commands globally
        event.getJDA().updateCommands().addCommands(
                Commands.slash(PLAYERS_COMMAND, "Shows the list of players currently online in Minecraft"),
                Commands.slash(CLEAR_CHAT_COMMAND, "Removes the past 100 messages from this channel"),
                Commands.slash(STATS_COMMAND, "Shows server player statistics and leaderboards")
                        .addOption(OptionType.STRING, "player", "Optional player name to view specific stats", false),
                Commands.slash(STATUS_COMMAND, "Displays server performance, TPS, RAM, and uptime"),
                Commands.slash(RESTART_COMMAND, "Gracefully restarts/stops the Minecraft server (Owner only)"),
                Commands.slash(CMD_COMMAND, "Executes a Minecraft console command with OP level 4 (Owner only)")
                        .addOption(OptionType.STRING, "command", "The command to execute (e.g. op, time set day)", true)
        ).queue(
                success -> LOGGER.info("Registered global slash commands (/players, /clearchat, /stats, /status, /restart, /cmd)."),
                error -> LOGGER.warn("Could not register global slash commands: {}", error.getMessage())
        );

        // Also register directly to the configured guild for instant visibility
        if (DiscordConfig.isValidChannelId(DiscordConfig.channelId))
        {
            final GuildMessageChannel channel = event.getJDA().getChannelById(GuildMessageChannel.class, DiscordConfig.channelId);
            if (channel != null)
            {
                channel.getGuild().upsertCommand(PLAYERS_COMMAND, "Shows the list of players currently online in Minecraft").queue(
                        success -> LOGGER.info("Registered /players slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /players command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(CLEAR_CHAT_COMMAND, "Removes the past 100 messages from this channel").queue(
                        success -> LOGGER.info("Registered /clearchat slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /clearchat command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(
                        Commands.slash(STATS_COMMAND, "Shows server player statistics and leaderboards")
                                .addOption(OptionType.STRING, "player", "Optional player name to view specific stats", false)
                ).queue(
                        success -> LOGGER.info("Registered /stats slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /stats command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(STATUS_COMMAND, "Displays server performance, TPS, RAM, and uptime").queue(
                        success -> LOGGER.info("Registered /status slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /status command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(RESTART_COMMAND, "Gracefully restarts/stops the Minecraft server (Owner only)").queue(
                        success -> LOGGER.info("Registered /restart slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /restart command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(
                        Commands.slash(CMD_COMMAND, "Executes a Minecraft console command with OP level 4 (Owner only)")
                                .addOption(OptionType.STRING, "command", "The command to execute", true)
                ).queue(
                        success -> LOGGER.info("Registered /cmd slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /cmd command: {}", error.getMessage())
                );
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(final SlashCommandInteractionEvent event)
    {
        if (CLEAR_CHAT_COMMAND.equals(event.getName()))
        {
            handleClearChatCommand(event);
            return;
        }
        if (STATS_COMMAND.equals(event.getName()))
        {
            handleStatsCommand(event);
            return;
        }
        if (STATUS_COMMAND.equals(event.getName()))
        {
            handleStatusCommand(event);
            return;
        }
        if (RESTART_COMMAND.equals(event.getName()))
        {
            handleRestartCommand(event);
            return;
        }
        if (CMD_COMMAND.equals(event.getName()))
        {
            handleCmdCommand(event);
            return;
        }
        if (!PLAYERS_COMMAND.equals(event.getName()))
        {
            return;
        }
        if (!DiscordConfig.respondToPlayersCommand)
        {
            event.reply("The /players command is currently disabled.").setEphemeral(true).queue();
            return;
        }

        // Defer reply immediately within Discord's 3-second window
        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage("Server is not available.").queue();
                return;
            }
            mc.execute(() -> {
                final List<ServerPlayer> players = mc.getPlayerList().getPlayers();
                final String reply;
                if (players.isEmpty())
                {
                    reply = "Nobody is online right now.";
                }
                else
                {
                    final String names = players.stream()
                            .map(p -> p.getGameProfile().getName())
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .collect(Collectors.joining(", "));
                    reply = "**" + players.size() + "/" + mc.getPlayerList().getMaxPlayers()
                            + " online:** " + names;
                }
                hook.sendMessage(reply).queue();
            });
        });
    }

    @Override
    public void onMessageReceived(final MessageReceivedEvent event)
    {
        // Loop guard: ignore bots and webhooks (outbound webhook messages land here)
        if (event.getAuthor().isBot() || event.isWebhookMessage())
        {
            if (!DiscordConfig.relayBotMessages)
            {
                return;
            }
        }

        // Only listen in the configured channel
        if (!event.getChannel().getId().equals(DiscordConfig.channelId))
        {
            return;
        }

        final String raw = event.getMessage().getContentDisplay().trim();

        // In case someone types !players or !clearchat as text, handle it
        if ("!players".equalsIgnoreCase(raw))
        {
            if (DiscordConfig.respondToPlayersCommand)
            {
                respondToTextPlayers(event);
            }
            return;
        }
        if ("!clearchat".equalsIgnoreCase(raw))
        {
            handleTextClearChat(event);
            return;
        }
        if ("!stats".equalsIgnoreCase(raw) || raw.toLowerCase().startsWith("!stats "))
        {
            if (DiscordConfig.respondToStatsCommand)
            {
                respondToTextStats(event, raw);
            }
            return;
        }
        if ("!status".equalsIgnoreCase(raw))
        {
            respondToTextStatus(event);
            return;
        }
        if ("!restart".equalsIgnoreCase(raw))
        {
            respondToTextRestart(event);
            return;
        }
        if (raw.toLowerCase().startsWith("!cmd "))
        {
            respondToTextCmd(event, raw.substring(5).trim());
            return;
        }

        if (raw.startsWith("/"))
        {
            // Never relay raw slash commands as chat
            return;
        }

        String content = clean(raw);
        if (content.isEmpty())
        {
            if (DiscordConfig.relayAttachments && !event.getMessage().getAttachments().isEmpty())
            {
                content = "(attachment)";
            }
            else
            {
                return;
            }
        }
        else if (DiscordConfig.relayAttachments && !event.getMessage().getAttachments().isEmpty())
        {
            content += " (attachment)";
        }

        final int max = DiscordConfig.maxRelayedLength;
        if (content.length() > max)
        {
            content = content.substring(0, Math.max(1, max - 3)) + "...";
        }

        final String author = event.getMember() != null
                ? clean(event.getMember().getEffectiveName())
                : clean(event.getAuthor().getEffectiveName());

        final Component line = DiscordMessageFormatter.toChatComponent(author, content);
        final MinecraftServer mc = server;
        if (mc != null)
        {
            mc.execute(() -> mc.getPlayerList().broadcastSystemMessage(line, false));
        }
    }

    private void handleClearChatCommand(final SlashCommandInteractionEvent event)
    {
        if (event.getMember() == null || !event.getMember().hasPermission(event.getGuildChannel(), net.dv8tion.jda.api.Permission.MESSAGE_MANAGE))
        {
            event.reply(":x: You need the 'Manage Messages' permission to use this command.").setEphemeral(true).queue();
            return;
        }

        final String userId = event.getUser().getId();
        event.reply(":warning: Are you sure you want to delete the past 100 messages from this channel?")
                .addActionRow(
                        net.dv8tion.jda.api.interactions.components.buttons.Button.danger(BTN_CONFIRM_PREFIX + userId, "Yes"),
                        net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(BTN_CANCEL_PREFIX + userId, "No")
                )
                .queue();
    }

    private void handleTextClearChat(final MessageReceivedEvent event)
    {
        if (!(event.getChannel() instanceof GuildMessageChannel guildChannel))
        {
            return;
        }
        if (event.getMember() == null || !event.getMember().hasPermission(guildChannel, net.dv8tion.jda.api.Permission.MESSAGE_MANAGE))
        {
            event.getMessage().reply(":x: You need the 'Manage Messages' permission to use this command.").queue();
            return;
        }

        final String userId = event.getAuthor().getId();
        guildChannel.sendMessage(":warning: Are you sure you want to delete the past 100 messages from this channel?")
                .setActionRow(
                        net.dv8tion.jda.api.interactions.components.buttons.Button.danger(BTN_CONFIRM_PREFIX + userId, "Yes"),
                        net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(BTN_CANCEL_PREFIX + userId, "No")
                )
                .queue();
    }

    @Override
    public void onButtonInteraction(final net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent event)
    {
        final String buttonId = event.getComponentId();
        if (!buttonId.startsWith(BTN_CONFIRM_PREFIX) && !buttonId.startsWith(BTN_CANCEL_PREFIX))
        {
            return;
        }

        final boolean isConfirm = buttonId.startsWith(BTN_CONFIRM_PREFIX);
        final String authorizedUserId = buttonId.substring(isConfirm ? BTN_CONFIRM_PREFIX.length() : BTN_CANCEL_PREFIX.length());
        final String clickerId = event.getUser().getId();

        if (!clickerId.equals(authorizedUserId))
        {
            if (event.getMember() == null || !event.getMember().hasPermission(event.getGuildChannel(), net.dv8tion.jda.api.Permission.MESSAGE_MANAGE))
            {
                event.reply(":x: You are not authorized to use this confirmation button.").setEphemeral(true).queue();
                return;
            }
        }

        if (!isConfirm)
        {
            event.editMessage(":white_check_mark: Message clearing cancelled.")
                    .setComponents()
                    .queue();
            return;
        }

        if (!(event.getChannel() instanceof GuildMessageChannel guildChannel))
        {
            event.editMessage(":x: Cannot clear messages outside a guild channel.").setComponents().queue();
            return;
        }

        event.editMessage(":hourglass_flowing_sand: Deleting the past 100 messages...")
                .setComponents()
                .queue(hook -> guildChannel.getHistory().retrievePast(100).queue(
                        messages -> {
                            if (messages.isEmpty())
                            {
                                hook.editOriginal(":information_source: No messages found to delete.").queue();
                                return;
                            }
                            guildChannel.purgeMessages(messages);
                            guildChannel.sendMessage(":wastebasket: Cleared " + messages.size() + " messages.")
                                    .queue(msg -> msg.delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS, null, ignored -> {}));
                        },
                        error -> {
                            LOGGER.error("Failed to retrieve past messages for /clearchat: {}", error.getMessage());
                            hook.editOriginal(":x: Failed to retrieve messages: " + error.getMessage()).queue();
                        }
                ));
    }

    private void handleStatsCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.respondToStatsCommand)
        {
            event.reply("The /stats command is currently disabled.").setEphemeral(true).queue();
            return;
        }

        final OptionMapping playerOption = event.getOption("player");
        final String targetPlayer = playerOption != null ? playerOption.getAsString().trim() : null;

        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage("Server is not available.").queue();
                return;
            }
            mc.execute(() -> {
                // Flush online player stats for fresh calculations
                for (final ServerPlayer p : mc.getPlayerList().getPlayers())
                {
                    try
                    {
                        p.getStats().save();
                    }
                    catch (final Exception ignored)
                    {
                    }
                }
                final MessageEmbed embed = (targetPlayer == null || targetPlayer.isEmpty())
                        ? PlayerStatsService.buildLeaderboardsEmbed(mc)
                        : PlayerStatsService.buildPlayerStatsEmbed(mc, targetPlayer);
                hook.sendMessageEmbeds(embed).queue();
            });
        });
    }

    private void respondToTextStats(final MessageReceivedEvent event, final String raw)
    {
        final MinecraftServer mc = server;
        if (mc == null)
        {
            return;
        }
        final String[] parts = raw.split("\\s+", 2);
        final String targetPlayer = parts.length > 1 ? parts[1].trim() : null;

        mc.execute(() -> {
            for (final ServerPlayer p : mc.getPlayerList().getPlayers())
            {
                try
                {
                    p.getStats().save();
                }
                catch (final Exception ignored)
                {
                }
            }
            final MessageEmbed embed = (targetPlayer == null || targetPlayer.isEmpty())
                    ? PlayerStatsService.buildLeaderboardsEmbed(mc)
                    : PlayerStatsService.buildPlayerStatsEmbed(mc, targetPlayer);
            event.getChannel().sendMessageEmbeds(embed).queue();
        });
    }

    private void handleStatusCommand(final SlashCommandInteractionEvent event)
    {
        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage("Server is not available.").queue();
                return;
            }
            final MessageEmbed embed = DiscordAdminService.buildStatusEmbed(mc);
            hook.sendMessageEmbeds(embed).queue();
        });
    }

    private void handleRestartCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.isOwner(event.getUser().getId()))
        {
            event.reply(":x: You are not authorized to restart the server.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage(":x: Server is not available.").queue();
                return;
            }
            hook.sendMessage(":arrows_counterclockwise: Server restart initiated by **" + event.getUser().getAsTag() + "**...").queue();
            DiscordAdminService.restartServer(mc, event.getUser().getAsTag());
        });
    }

    private void handleCmdCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.isOwner(event.getUser().getId()))
        {
            event.reply(":x: You are not authorized to execute operator commands.").setEphemeral(true).queue();
            return;
        }

        final OptionMapping cmdOpt = event.getOption("command");
        if (cmdOpt == null || cmdOpt.getAsString().isBlank())
        {
            event.reply(":x: Please specify a command to execute.").setEphemeral(true).queue();
            return;
        }

        final String command = cmdOpt.getAsString().trim();
        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage(":x: Server is not available.").queue();
                return;
            }
            DiscordAdminService.executeConsoleCommand(mc, command, result -> hook.sendMessage(result).queue());
        });
    }

    private void respondToTextStatus(final MessageReceivedEvent event)
    {
        final MinecraftServer mc = server;
        if (mc != null)
        {
            final MessageEmbed embed = DiscordAdminService.buildStatusEmbed(mc);
            event.getChannel().sendMessageEmbeds(embed).queue();
        }
    }

    private void respondToTextRestart(final MessageReceivedEvent event)
    {
        if (!DiscordConfig.isOwner(event.getAuthor().getId()))
        {
            event.getMessage().reply(":x: You are not authorized to restart the server.").queue();
            return;
        }
        final MinecraftServer mc = server;
        if (mc != null)
        {
            event.getChannel().sendMessage(":arrows_counterclockwise: Server restart initiated by **" + event.getAuthor().getAsTag() + "**...").queue();
            DiscordAdminService.restartServer(mc, event.getAuthor().getAsTag());
        }
    }

    private void respondToTextCmd(final MessageReceivedEvent event, final String command)
    {
        if (!DiscordConfig.isOwner(event.getAuthor().getId()))
        {
            event.getMessage().reply(":x: You are not authorized to execute operator commands.").queue();
            return;
        }
        final MinecraftServer mc = server;
        if (mc != null)
        {
            DiscordAdminService.executeConsoleCommand(mc, command, result -> event.getChannel().sendMessage(result).queue());
        }
    }

    private void respondToTextPlayers(final MessageReceivedEvent event)
    {
        final MinecraftServer mc = server;
        if (mc == null)
        {
            return;
        }
        mc.execute(() -> {
            final List<ServerPlayer> players = mc.getPlayerList().getPlayers();
            final String text;
            if (players.isEmpty())
            {
                text = "Nobody is online right now.";
            }
            else
            {
                final String names = players.stream()
                        .map(p -> p.getGameProfile().getName())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.joining(", "));
                text = "**" + players.size() + "/" + mc.getPlayerList().getMaxPlayers()
                        + " online:** " + names;
            }
            event.getChannel().sendMessage(text).queue();
        });
    }

    private static String clean(final String input)
    {
        if (input == null || input.isEmpty())
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++)
        {
            final char c = input.charAt(i);
            if (c == SECTION_SIGN)
            {
                i++;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t')
            {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c))
            {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
