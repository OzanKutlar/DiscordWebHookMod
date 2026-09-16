package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    private static final String MENTIONS_COMMAND = "mentions";
    private static final String OVERVIEW_COMMAND = "overview";
    /** Discord will not accept more than 25 autocomplete suggestions. */
    private static final int MAX_AUTOCOMPLETE_CHOICES = 25;

    private final MinecraftServer server;

    public DiscordEventListener(final MinecraftServer server)
    {
        this.server = server;
    }

    @Override
    public void onReady(final ReadyEvent event)
    {
        LOGGER.info("Discord bot connected as {}", event.getJDA().getSelfUser().getAsTag());
        MentionResolver.refresh(event.getJDA());

        // Register slash commands globally
        event.getJDA().updateCommands().addCommands(
                Commands.slash(PLAYERS_COMMAND, "Shows the list of players currently online in Minecraft"),
                Commands.slash(CLEAR_CHAT_COMMAND, "Removes the past 100 messages from this channel"),
                statsCommandData(),
                mentionsCommandData(),
                overviewCommandData(),
                Commands.slash(STATUS_COMMAND, "Displays server performance, TPS, RAM, and uptime"),
                Commands.slash(RESTART_COMMAND, "Gracefully restarts/stops the Minecraft server (Owner only)"),
                Commands.slash(CMD_COMMAND, "Executes a Minecraft console command with OP level 4 (Owner only)")
                        .addOption(OptionType.STRING, "command", "The command to execute (e.g. op, time set day)", true)
        ).queue(
                success -> LOGGER.info("Registered global slash commands (/players, /clearchat, /stats, /mentions, /overview, /status, /restart, /cmd)."),
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
                channel.getGuild().upsertCommand(statsCommandData()).queue(
                        success -> LOGGER.info("Registered /stats slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /stats command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(mentionsCommandData()).queue(
                        success -> LOGGER.info("Registered /mentions slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /mentions command: {}", error.getMessage())
                );
                channel.getGuild().upsertCommand(overviewCommandData()).queue(
                        success -> LOGGER.info("Registered /overview slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific /overview command: {}", error.getMessage())
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
        if (MENTIONS_COMMAND.equals(event.getName()))
        {
            handleMentionsCommand(event);
            return;
        }
        if (OVERVIEW_COMMAND.equals(event.getName()))
        {
            handleOverviewCommand(event);
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
            // Health and experience are live entity state, so read them on the server thread.
            mc.execute(() -> {
                final MessageEmbed embed = PlayerListService.buildPlayersEmbed(mc);
                hook.sendMessageEmbeds(embed).queue();
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
        if ("!overview".equalsIgnoreCase(raw))
        {
            respondToTextOverview(event);
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

    /* ------------------------------------------------------------------ */
    /* Mention settings, restricted to the configured owner id.            */
    /* ------------------------------------------------------------------ */

    private static SlashCommandData mentionsCommandData()
    {
        return Commands.slash(MENTIONS_COMMAND, "Control Minecraft to Discord @mentions (Owner only)")
                .addSubcommands(
                        new SubcommandData("status", "Show the current mention settings"),
                        new SubcommandData("allow", "Turn @name resolution on or off")
                                .addOption(OptionType.BOOLEAN, "value", "Whether players may ping Discord users", true),
                        new SubcommandData("everyone", "Allow @everyone and @here from Minecraft")
                                .addOption(OptionType.BOOLEAN, "value", "Whether @everyone is permitted", true),
                        new SubcommandData("max", "Maximum mentions resolved per message")
                                .addOption(OptionType.INTEGER, "value", "Between 0 and 10", true),
                        new SubcommandData("cooldown", "Seconds between one player's pings")
                                .addOption(OptionType.INTEGER, "value", "Between 0 and 3600, 0 disables", true));
    }

    private void handleMentionsCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.isOwner(event.getUser().getId()))
        {
            event.reply(":x: You are not authorized to change mention settings.").setEphemeral(true).queue();
            return;
        }

        final String subcommand = event.getSubcommandName();
        if (subcommand == null || "status".equals(subcommand))
        {
            event.reply("```\n" + DiscordConfig.mentionStatusText()
                    + "\nknown Discord names cached: " + MentionResolver.directorySize() + "\n```").queue();
            return;
        }

        final OptionMapping value = event.getOption("value");
        if (value == null)
        {
            event.reply(":x: Missing value.").setEphemeral(true).queue();
            return;
        }

        switch (subcommand)
        {
            case "allow":
                handleAllowMentions(event, value.getAsBoolean());
                return;
            case "everyone":
                reportToggle(event, DiscordConfig.setAllowEveryoneMention(value.getAsBoolean()),
                        "@everyone from Minecraft", String.valueOf(value.getAsBoolean()));
                return;
            case "max":
                reportToggle(event, DiscordConfig.setMaxMentionsPerMessage(value.getAsInt()),
                        "Maximum mentions per message", String.valueOf(value.getAsInt()));
                return;
            case "cooldown":
                reportToggle(event, DiscordConfig.setMentionCooldownSeconds(value.getAsInt()),
                        "Mention cooldown (seconds)", String.valueOf(value.getAsInt()));
                return;
            default:
                event.reply(":x: Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    /**
     * Toggling mentions changes the requested gateway intents, so the bot must
     * reconnect. The reply is sent before the restart because the restart tears
     * down the JDA instance handling this interaction.
     */
    private void handleAllowMentions(final SlashCommandInteractionEvent event, final boolean enable)
    {
        if (!DiscordConfig.setAllowMentions(enable))
        {
            event.reply(":x: Could not write the config file. Check the server log.").setEphemeral(true).queue();
            return;
        }

        final StringBuilder reply = new StringBuilder(256);
        reply.append(":white_check_mark: Mentions set to **").append(enable).append("**. Reconnecting the bot...");
        if (enable)
        {
            reply.append("\n:warning: If the bot does not come back, enable the **Server Members Intent** ")
                    .append("in the Developer Portal under Bot > Privileged Gateway Intents.");
        }
        if (enable && DiscordConfig.suppressMentions)
        {
            reply.append("\n:information_source: `suppressMentions` is on, so nothing will resolve until you turn it off.");
        }

        event.reply(reply.toString()).queue(
                hook -> DiscordBridge.botManager().restart(),
                error -> DiscordBridge.botManager().restart());
    }

    private void reportToggle(final SlashCommandInteractionEvent event, final boolean ok,
                              final String label, final String value)
    {
        if (!ok)
        {
            event.reply(":x: Could not apply that change. Check the value range and the server log.")
                    .setEphemeral(true).queue();
            return;
        }
        event.reply(":white_check_mark: " + label + " set to **" + value + "**.").queue();
    }

    /* ------------------------------------------------------------------ */
    /* Member cache upkeep for the mention directory.                      */
    /* ------------------------------------------------------------------ */

    @Override
    public void onGuildMemberJoin(final net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent event)
    {
        MentionResolver.refresh(event.getJDA());
    }

    @Override
    public void onGuildMemberRemove(final net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent event)
    {
        MentionResolver.refresh(event.getJDA());
    }

    @Override
    public void onGuildMemberUpdateNickname(
            final net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent event)
    {
        MentionResolver.refresh(event.getJDA());
    }

    /**
     * Discord forbids invoking a base command that declares subcommands, so the
     * summary lives under an explicit {@code overview} subcommand rather than a
     * bare {@code /stats}.
     */
    private static SlashCommandData statsCommandData()
    {
        return Commands.slash(STATS_COMMAND, "Server statistics, leaderboards and player cards")
                .addSubcommands(
                        new SubcommandData("overview", "Top players across the headline statistics"),
                        new SubcommandData("player", "Lifetime statistics for one player")
                                .addOption(OptionType.STRING, "name", "The player to look up", true, false),
                        new SubcommandData("category", "Full leaderboard for one statistic")
                                .addOption(OptionType.STRING, "name", "The statistic to rank players by", true, true));
    }

    /**
     * Suggests category ids. Runs on a gateway thread, so it deliberately reads
     * only the cached snapshot and never touches the world save.
     */
    @Override
    public void onCommandAutoCompleteInteraction(final CommandAutoCompleteInteractionEvent event)
    {
        if (!STATS_COMMAND.equals(event.getName())
                || !"category".equals(event.getSubcommandName())
                || !"name".equals(event.getFocusedOption().getName()))
        {
            return;
        }

        final String typed = event.getFocusedOption().getValue().trim().toLowerCase(Locale.ROOT);
        final List<Command.Choice> choices = new ArrayList<>();
        for (final String id : PlayerStatsService.suggestibleCategoryIds())
        {
            if (choices.size() >= MAX_AUTOCOMPLETE_CHOICES)
            {
                break;
            }
            if (typed.isEmpty() || id.contains(typed))
            {
                choices.add(new Command.Choice(id, id));
            }
        }
        event.replyChoices(choices).queue();
    }

    private void handleStatsCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.respondToStatsCommand)
        {
            event.reply("The /stats command is currently disabled.").setEphemeral(true).queue();
            return;
        }

        final String subcommand = event.getSubcommandName();
        final OptionMapping nameOption = event.getOption("name");
        final String argument = nameOption != null ? nameOption.getAsString().trim() : null;

        if ("category".equals(subcommand))
        {
            final Optional<StatCategory> resolved = StatCategories.resolve(argument);
            if (resolved.isEmpty())
            {
                event.reply(":x: Unknown statistic `" + argument + "`. Start typing to see the available ones.")
                        .setEphemeral(true).queue();
                return;
            }
            deferAndBuild(event, mc -> PlayerStatsService.buildCategoryLeaderboardEmbed(mc, resolved.get()));
            return;
        }

        if ("player".equals(subcommand))
        {
            if (argument == null || argument.isEmpty())
            {
                event.reply(":x: Please give a player name.").setEphemeral(true).queue();
                return;
            }
            deferAndBuild(event, mc -> PlayerStatsService.buildPlayerStatsEmbed(mc, argument));
            return;
        }

        deferAndBuild(event, PlayerStatsService::buildOverviewEmbed);
    }

    /**
     * Defers the interaction, flushes stats on the server thread, then replies.
     */
    private void deferAndBuild(final SlashCommandInteractionEvent event,
                               final java.util.function.Function<MinecraftServer, MessageEmbed> builder)
    {
        event.deferReply().queue(hook -> {
            final MinecraftServer mc = server;
            if (mc == null)
            {
                hook.sendMessage("Server is not available.").queue();
                return;
            }
            mc.execute(() -> {
                flushOnlineStats(mc);
                hook.sendMessageEmbeds(builder.apply(mc)).queue();
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

        final String[] parts = raw.split("\\s+", 3);
        final String first = parts.length > 1 ? parts[1].trim() : "";
        final String second = parts.length > 2 ? parts[2].trim() : "";

        if ("category".equalsIgnoreCase(first))
        {
            final Optional<StatCategory> resolved = StatCategories.resolve(second);
            if (resolved.isEmpty())
            {
                event.getMessage().reply(":x: Unknown statistic `" + second + "`.").queue();
                return;
            }
            sendStatsEmbed(event, mc, server2 -> PlayerStatsService.buildCategoryLeaderboardEmbed(server2, resolved.get()));
            return;
        }

        // "!stats player <name>" and the legacy "!stats <name>" both look up a player.
        final String targetPlayer = "player".equalsIgnoreCase(first) ? second : first;
        if (targetPlayer.isEmpty())
        {
            sendStatsEmbed(event, mc, PlayerStatsService::buildOverviewEmbed);
            return;
        }
        sendStatsEmbed(event, mc, server2 -> PlayerStatsService.buildPlayerStatsEmbed(server2, targetPlayer));
    }

    private void sendStatsEmbed(final MessageReceivedEvent event, final MinecraftServer mc,
                                final java.util.function.Function<MinecraftServer, MessageEmbed> builder)
    {
        mc.execute(() -> {
            flushOnlineStats(mc);
            event.getChannel().sendMessageEmbeds(builder.apply(mc)).queue();
        });
    }

    /**
     * Writes online players' stats to disk and drops the cached snapshot, so the
     * numbers we report are current. Must run on the server thread.
     */
    private static void flushOnlineStats(final MinecraftServer mc)
    {
        PlayerStatsService.flushOnlineStats(mc);
    }

    /* ------------------------------------------------------------------ */
    /* End-of-season overview.                                             */
    /* ------------------------------------------------------------------ */

    private static SlashCommandData overviewCommandData()
    {
        return Commands.slash(OVERVIEW_COMMAND,
                "Build an LLM prompt recapping the whole server history (sent to your DMs)");
    }

    /**
     * Validates the request, then hands off to {@link OverviewService}. The reply
     * is deferred ephemerally so progress and any fallback file stay private.
     */
    private void handleOverviewCommand(final SlashCommandInteractionEvent event)
    {
        if (!DiscordConfig.respondToOverviewCommand)
        {
            event.reply("The /overview command is currently disabled.").setEphemeral(true).queue();
            return;
        }
        final GuildMessageChannel channel = OverviewService.bridgeChannel(event.getJDA());
        if (channel == null)
        {
            event.reply(":x: The bridge channel is not configured, or I can't see it.").setEphemeral(true).queue();
            return;
        }
        if (!OverviewService.isAuthorized(event.getMember(), channel, event.getUser().getId()))
        {
            event.reply(":x: You need the 'Manage Messages' permission in the bridge channel to build an overview.")
                    .setEphemeral(true).queue();
            return;
        }
        final String refusal = OverviewService.tryAcquire(event.getUser().getId());
        if (refusal != null)
        {
            event.reply(":hourglass: " + refusal).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(
                hook -> OverviewService.run(server, channel, event.getUser(), OverviewService.hookReporter(hook)),
                error -> {
                    OverviewService.release(false);
                    LOGGER.warn("Could not acknowledge /overview: {}", error.getMessage());
                });
    }

    private void respondToTextOverview(final MessageReceivedEvent event)
    {
        if (!DiscordConfig.respondToOverviewCommand)
        {
            event.getMessage().reply("The overview command is currently disabled.").queue();
            return;
        }
        final GuildMessageChannel channel = OverviewService.bridgeChannel(event.getJDA());
        if (channel == null)
        {
            event.getMessage().reply(":x: The bridge channel is not configured, or I can't see it.").queue();
            return;
        }
        if (!OverviewService.isAuthorized(event.getMember(), channel, event.getAuthor().getId()))
        {
            event.getMessage().reply(":x: You need the 'Manage Messages' permission to build an overview.").queue();
            return;
        }
        final String refusal = OverviewService.tryAcquire(event.getAuthor().getId());
        if (refusal != null)
        {
            event.getMessage().reply(":hourglass: " + refusal).queue();
            return;
        }
        event.getMessage().reply(":scroll: Building the server overview. I'll DM it to you when it's ready.").queue(
                status -> OverviewService.run(server, channel, event.getAuthor(), OverviewService.messageReporter(status)),
                error -> {
                    OverviewService.release(false);
                    LOGGER.warn("Could not start !overview: {}", error.getMessage());
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
            final MessageEmbed embed = PlayerListService.buildPlayersEmbed(mc);
            event.getChannel().sendMessageEmbeds(embed).queue();
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
