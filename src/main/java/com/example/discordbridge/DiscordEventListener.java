package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
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

    private final MinecraftServer server;

    public DiscordEventListener(final MinecraftServer server)
    {
        this.server = server;
    }

    @Override
    public void onReady(final @NotNull ReadyEvent event)
    {
        LOGGER.info("Discord bot connected as {}", event.getJDA().getSelfUser().getAsTag());

        // Register slash command globally
        event.getJDA().updateCommands().addCommands(
                Commands.slash(PLAYERS_COMMAND, "Shows the list of players currently online in Minecraft")
        ).queue(
                success -> LOGGER.info("Registered global /players slash command."),
                error -> LOGGER.warn("Could not register global slash command: {}", error.getMessage())
        );

        // Also register directly to the configured guild for instant visibility
        if (DiscordConfig.isValidChannelId(DiscordConfig.channelId))
        {
            final GuildMessageChannel channel = event.getJDA().getChannelById(GuildMessageChannel.class, DiscordConfig.channelId);
            if (channel != null)
            {
                channel.getGuild().upsertCommand(PLAYERS_COMMAND, "Shows the list of players currently online in Minecraft").queue(
                        success -> LOGGER.info("Registered /players slash command in guild '{}' for instant use.", channel.getGuild().getName()),
                        error -> LOGGER.debug("Could not register guild-specific slash command: {}", error.getMessage())
                );
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(final @NotNull SlashCommandInteractionEvent event)
    {
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
    public void onMessageReceived(final @NotNull MessageReceivedEvent event)
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

        // In case someone types !players as text, handle it
        if ("!players".equalsIgnoreCase(raw))
        {
            if (DiscordConfig.respondToPlayersCommand)
            {
                respondToTextPlayers(event);
            }
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
