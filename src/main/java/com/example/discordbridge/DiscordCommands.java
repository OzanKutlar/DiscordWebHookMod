package com.example.discordbridge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.function.BooleanSupplier;

/**
 * Operator-only configuration commands under {@code /discordbridge}.
 */
@Mod.EventBusSubscriber(modid = DiscordBridge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DiscordCommands
{
    private static final int PERMISSION_LEVEL = 4;
    private static final String WRITE_FAILED = "Could not write the config file. Check the server log.";

    private DiscordCommands()
    {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        event.getDispatcher().register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build()
    {
        return Commands.literal("discordbridge")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("status").executes(DiscordCommands::status))
                .then(Commands.literal("test").executes(DiscordCommands::test))
                .then(Commands.literal("enabled")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> toggle(ctx, "Relay",
                                        () -> DiscordConfig.setEnabled(BoolArgumentType.getBool(ctx, "value"))))))
                .then(Commands.literal("url")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(DiscordCommands::setUrl)))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(DiscordCommands::setName)))
                .then(Commands.literal("avatar")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(DiscordCommands::setAvatar)))
                .then(Commands.literal("events")
                        .then(eventToggle("join"))
                        .then(eventToggle("leave"))
                        .then(eventToggle("chat"))
                        .then(eventToggle("death"))
                        .then(eventToggle("commands"))
                        .then(eventToggle("advancements"))
                        .then(eventToggle("server"))
                        .then(eventToggle("embeds")))
                .then(Commands.literal("safety")
                        .then(Commands.literal("sanitizeMarkdown")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> toggle(ctx, "Markdown escaping",
                                                () -> DiscordConfig.setSanitizeMarkdown(
                                                        BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("suppressMentions")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> toggle(ctx, "Mention suppression",
                                                () -> DiscordConfig.setSuppressMentions(
                                                        BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("maskUrlInStatus")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> toggle(ctx, "URL masking",
                                                () -> DiscordConfig.setMaskUrlInStatus(
                                                        BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("allowCustomWebhookHost")
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(ctx -> toggle(ctx, "Custom webhook hosts",
                                                () -> DiscordConfig.setAllowCustomWebhookHost(
                                                        BoolArgumentType.getBool(ctx, "value")))))))
                .then(mentions())
                .then(inbound());
    }

    /**
     * Operator-side mirror of the Discord {@code /mentions} command.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> mentions()
    {
        return Commands.literal("mentions")
                .then(Commands.literal("status").executes(DiscordCommands::mentionStatus))
                .then(Commands.literal("allow")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(DiscordCommands::setAllowMentions)))
                .then(Commands.literal("everyone")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> toggle(ctx, "@everyone from Minecraft",
                                        () -> DiscordConfig.setAllowEveryoneMention(
                                                BoolArgumentType.getBool(ctx, "value"))))))
                .then(Commands.literal("max")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 10))
                                .executes(ctx -> setInt(ctx, "Maximum mentions per message",
                                        DiscordConfig.setMaxMentionsPerMessage(
                                                IntegerArgumentType.getInteger(ctx, "value"))))))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 3600))
                                .executes(ctx -> setInt(ctx, "Mention cooldown (seconds)",
                                        DiscordConfig.setMentionCooldownSeconds(
                                                IntegerArgumentType.getInteger(ctx, "value"))))));
    }

    private static int mentionStatus(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String text = "Discord Bridge mentions\n  "
                + DiscordConfig.mentionStatusText().replace("\n", "\n  ")
                + "\n  known Discord names cached: " + MentionResolver.directorySize();
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Toggling mentions changes which gateway intents are requested, so the bot
     * has to reconnect for the change to take effect.
     */
    private static int setAllowMentions(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final boolean value = BoolArgumentType.getBool(ctx, "value");

        if (!DiscordConfig.setAllowMentions(value))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        DiscordBridge.botManager().restart();

        source.sendSuccess(() -> Component.literal("Mentions set to " + value + ". Reconnecting the bot...")
                .withStyle(ChatFormatting.GREEN), false);
        if (value)
        {
            source.sendSuccess(() -> Component.literal(
                    "If the bot fails to connect, enable the Server Members Intent in the Discord "
                            + "Developer Portal under Bot > Privileged Gateway Intents.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (value && DiscordConfig.suppressMentions)
        {
            source.sendSuccess(() -> Component.literal(
                    "Note: suppressMentions is on, so nothing will actually resolve until you turn it off.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setInt(final CommandContext<CommandSourceStack> ctx, final String label, final boolean ok)
    {
        final CommandSourceStack source = ctx.getSource();
        if (!ok)
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        final int value = IntegerArgumentType.getInteger(ctx, "value");
        source.sendSuccess(() -> Component.literal(label + " set to " + value + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> eventToggle(final String key)
    {
        return Commands.literal(key)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> toggle(ctx, "Relay for " + key,
                                () -> DiscordConfig.setAnnounce(key, BoolArgumentType.getBool(ctx, "value")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> inbound()
    {
        return Commands.literal("inbound")
                .then(Commands.literal("status").executes(DiscordCommands::inboundStatus))
                .then(Commands.literal("enabled")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(DiscordCommands::setInboundEnabled)))
                .then(Commands.literal("token")
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(DiscordCommands::setBotToken)))
                .then(Commands.literal("channel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(DiscordCommands::setChannel)));
    }

    private static int inboundStatus(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final StringBuilder text = new StringBuilder(192);
        text.append("Discord Bridge inbound relay\n");
        text.append("  enabled: ").append(DiscordConfig.inboundEnabled).append('\n');
        text.append("  gateway status: ").append(DiscordBridge.botManager().getStatusText()).append('\n');
        text.append("  token: ").append(DiscordConfig.botToken.isBlank() ? "not set" : "set").append('\n');
        text.append("  channel: ")
                .append(DiscordConfig.channelId.isBlank() ? "<not set>" : DiscordConfig.channelId).append('\n');
        text.append("  relayBotMessages: ").append(DiscordConfig.relayBotMessages)
                .append(" relayAttachments: ").append(DiscordConfig.relayAttachments)
                .append(" respondToPlayersCommand: ").append(DiscordConfig.respondToPlayersCommand)
                .append(" respondToStatsCommand: ").append(DiscordConfig.respondToStatsCommand);

        source.sendSuccess(() -> Component.literal(text.toString()).withStyle(ChatFormatting.GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setInboundEnabled(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final boolean value = BoolArgumentType.getBool(ctx, "value");

        if (value && !DiscordConfig.isInboundConfigured())
        {
            source.sendFailure(Component.literal(
                    "Set a bot token and a channel id first: /discordbridge inbound token <token> "
                            + "and /discordbridge inbound channel <id>."));
            return 0;
        }
        if (!DiscordConfig.setInboundEnabled(value))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        DiscordBridge.botManager().restart();
        source.sendSuccess(() -> Component.literal("Inbound relay set to " + value + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setBotToken(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String token = StringArgumentType.getString(ctx, "token").trim();

        if (token.length() < 20)
        {
            source.sendFailure(Component.literal("That does not look like a bot token."));
            return 0;
        }
        if (source.getEntity() != null)
        {
            source.sendFailure(Component.literal(
                    "Warning: you ran this in game, so the token is now sitting in your chat log. "
                            + "Prefer the server console. Rotate the token in the Developer Portal if anyone saw it."));
        }
        if (!DiscordConfig.setBotToken(token))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        DiscordBridge.botManager().restart();
        source.sendSuccess(() -> Component.literal("Bot token stored.").withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setChannel(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String id = StringArgumentType.getString(ctx, "id").trim();

        if (!DiscordConfig.isValidChannelId(id))
        {
            source.sendFailure(Component.literal(
                    "That is not a channel id. Enable Developer Mode in Discord, then right-click the channel "
                            + "and choose Copy Channel ID."));
            return 0;
        }
        if (!DiscordConfig.setChannelId(id))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        DiscordBridge.botManager().restart();
        source.sendSuccess(() -> Component.literal("Inbound channel set to " + id + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final StringBuilder text = new StringBuilder(256);
        text.append("Discord Bridge status\n");
        text.append("  enabled: ").append(DiscordConfig.enabled).append('\n');
        text.append("  webhook: ").append(DiscordConfig.displayWebhookUrl()).append('\n');
        text.append("  name: ").append(DiscordConfig.username.isBlank() ? "<webhook default>" : DiscordConfig.username)
                .append('\n');
        text.append("  avatar: ").append(DiscordConfig.avatarUrl.isBlank() ? "<none>" : DiscordConfig.avatarUrl)
                .append('\n');
        text.append("  events: join=").append(DiscordConfig.announceJoin)
                .append(" leave=").append(DiscordConfig.announceLeave)
                .append(" chat=").append(DiscordConfig.announceChat)
                .append(" death=").append(DiscordConfig.announceDeath)
                .append(" commands=").append(DiscordConfig.announceCommands)
                .append(" advancements=").append(DiscordConfig.announceAdvancements)
                .append(" server=").append(DiscordConfig.announceServerStart && DiscordConfig.announceServerStop)
                .append(" embeds=").append(DiscordConfig.useRichEmbeds).append('\n');
        text.append("  safety: sanitizeMarkdown=").append(DiscordConfig.sanitizeMarkdown)
                .append(" suppressMentions=").append(DiscordConfig.suppressMentions)
                .append(" maskUrlInStatus=").append(DiscordConfig.maskUrlInStatus)
                .append(" allowCustomWebhookHost=").append(DiscordConfig.allowCustomWebhookHost);

        source.sendSuccess(() -> Component.literal(text.toString()).withStyle(ChatFormatting.GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int test(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Sending a test message...").withStyle(ChatFormatting.GRAY), false);

        DiscordBridge.sender().sendWithResult(
                "Discord Bridge test message.",
                failure -> server.execute(() -> {
                    if (failure == null)
                    {
                        source.sendSuccess(() -> Component.literal("Test message delivered.")
                                .withStyle(ChatFormatting.GREEN), false);
                    }
                    else
                    {
                        source.sendFailure(Component.literal("Test failed: " + failure));
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int setUrl(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String url = StringArgumentType.getString(ctx, "url").trim();

        if (!DiscordConfig.isValidWebhookUrl(url))
        {
            source.sendFailure(Component.literal(
                    "That does not look like a Discord webhook URL. Expected https://discord.com/api/webhooks/<id>/<token> "
                            + "(or enable /discordbridge safety allowCustomWebhookHost true for testing endpoints)."));
            return 0;
        }
        if (!DiscordConfig.setWebhookUrl(url))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Webhook URL set to " + DiscordConfig.displayWebhookUrl())
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setName(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String name = StringArgumentType.getString(ctx, "name").trim();

        if (name.length() > 80)
        {
            source.sendFailure(Component.literal("Discord limits webhook names to 80 characters."));
            return 0;
        }
        if (!DiscordConfig.setUsername(name))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Webhook display name set to \"" + name + "\".")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setAvatar(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String url = StringArgumentType.getString(ctx, "url").trim();

        if (!url.isEmpty() && !url.startsWith("https://"))
        {
            source.sendFailure(Component.literal("The avatar URL must start with https:// (or be empty to clear it)."));
            return 0;
        }
        if (!DiscordConfig.setAvatarUrl(url))
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(url.isEmpty() ? "Avatar cleared." : "Avatar URL updated.")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggle(final CommandContext<CommandSourceStack> ctx, final String label,
                              final BooleanSupplier mutation)
    {
        final CommandSourceStack source = ctx.getSource();
        final boolean value = BoolArgumentType.getBool(ctx, "value");

        if (!mutation.getAsBoolean())
        {
            source.sendFailure(Component.literal(WRITE_FAILED));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(label + " set to " + value + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return Command.SINGLE_SUCCESS;
    }
}
