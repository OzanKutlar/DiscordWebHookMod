package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.Locale;

/**
 * Config spec plus a cached, plain-field view of it.
 *
 * <p>Event handlers read the cached fields so the hot path never touches the
 * config lock. The cache is refreshed on load, on reload, and after any
 * in-game mutation.</p>
 */
@Mod.EventBusSubscriber(modid = DiscordBridge.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DiscordConfig
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch. When false, nothing is ever sent to Discord.")
            .define("enabled", true);

    private static final ForgeConfigSpec.ConfigValue<String> WEBHOOK_URL = BUILDER
            .comment("Discord webhook URL. Treat this as a password: anyone holding it can post to your channel.",
                     "Set it from the server console with: discordbridge url <url>")
            .define("webhookUrl", "");

    private static final ForgeConfigSpec.ConfigValue<String> USERNAME = BUILDER
            .comment("Display name the webhook posts under. Leave blank to use the webhook's own name.")
            .define("username", "Minecraft Server");

    private static final ForgeConfigSpec.ConfigValue<String> AVATAR_URL = BUILDER
            .comment("Optional avatar image URL for the webhook messages.")
            .define("avatarUrl", "");

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_JOIN = BUILDER
            .comment("Relay player join events.")
            .define("announceJoin", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_LEAVE = BUILDER
            .comment("Relay player leave events.")
            .define("announceLeave", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_CHAT = BUILDER
            .comment("Relay in-game chat messages.")
            .define("announceChat", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_DEATH = BUILDER
            .comment("Relay player death messages.")
            .define("announceDeath", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_COMMANDS = BUILDER
            .comment("Relay commands executed by players and the server console.")
            .define("announceCommands", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_ADVANCEMENTS = BUILDER
            .comment("Relay player advancement / achievement completions.")
            .define("announceAdvancements", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_SERVER_START = BUILDER
            .comment("Relay server startup events.")
            .define("announceServerStart", true);

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_SERVER_STOP = BUILDER
            .comment("Relay server stopping events.")
            .define("announceServerStop", true);

    private static final ForgeConfigSpec.BooleanValue USE_RICH_EMBEDS = BUILDER
            .comment("Use rich Discord embeds for lifecycle, join/leave, deaths, and advancements.")
            .define("useRichEmbeds", true);

    private static final ForgeConfigSpec.BooleanValue SANITIZE_MARKDOWN = BUILDER
            .comment("Escape Discord markdown (* _ ~ ` | > backslash) in relayed text.",
                     "OFF by default. Turn on if untrusted players can chat: without it a player",
                     "can inject formatting, spoilers or fake quotes into your Discord channel.")
            .define("sanitizeMarkdown", false);

    private static final ForgeConfigSpec.BooleanValue SUPPRESS_MENTIONS = BUILDER
            .comment("Neutralise @everyone / @here and tell Discord to parse no mentions at all.",
                     "OFF by default. Turn on if untrusted players can chat: without it any player",
                     "can ping your whole Discord server by typing @everyone in Minecraft chat.")
            .define("suppressMentions", false);

    private static final ForgeConfigSpec.BooleanValue MASK_URL_IN_STATUS = BUILDER
            .comment("Show the webhook URL masked in '/discordbridge status'.",
                     "OFF by default. Turn on if non-owners have operator level 4: without it the",
                     "full webhook secret is printed into their chat log.")
            .define("maskUrlInStatus", false);

    private static final ForgeConfigSpec.BooleanValue ALLOW_CUSTOM_WEBHOOK_HOST = BUILDER
            .comment("Allow any https host as the webhook target instead of only discord.com.",
                     "Useful for pointing at https://webhook.site while testing. Keep it off otherwise.")
            .define("allowCustomWebhookHost", false);

    static
    {
        BUILDER.comment("Discord -> Minecraft relay. Requires a bot application, not just a webhook.")
                .push("inbound");
    }

    private static final ForgeConfigSpec.BooleanValue INBOUND_ENABLED = BUILDER
            .comment("Relay messages from a Discord channel into Minecraft chat.",
                     "Requires botToken and channelId to be set first.")
            .define("inboundEnabled", false);

    private static final ForgeConfigSpec.ConfigValue<String> BOT_TOKEN = BUILDER
            .comment("Discord bot token. This is a stronger secret than the webhook URL:",
                     "it lets the holder act as the bot in every server the bot has joined.",
                     "It is never printed by any command and never written to the log.")
            .define("botToken", "");

    private static final ForgeConfigSpec.ConfigValue<String> CHANNEL_ID = BUILDER
            .comment("Channel to read from. Enable Developer Mode in Discord, then right-click the channel > Copy Channel ID.")
            .define("channelId", "");

    private static final ForgeConfigSpec.IntValue MAX_RELAYED_LENGTH = BUILDER
            .comment("Relayed Discord messages longer than this are truncated.")
            .defineInRange("maxRelayedLength", 256, 16, 512);

    private static final ForgeConfigSpec.BooleanValue RELAY_BOT_MESSAGES = BUILDER
            .comment("Relay messages posted by bots and webhooks.",
                     "Keep this OFF: this mod's own outbound webhook posts are webhook messages,",
                     "so turning it on makes the bridge echo itself in a loop.")
            .define("relayBotMessages", false);

    private static final ForgeConfigSpec.BooleanValue RELAY_ATTACHMENTS = BUILDER
            .comment("Append an (attachment) marker for image and file posts.")
            .define("relayAttachments", true);

    private static final ForgeConfigSpec.BooleanValue RESPOND_TO_PLAYERS_COMMAND = BUILDER
            .comment("Answer '!players' in the Discord channel with the current online player list.")
            .define("respondToPlayersCommand", true);

    private static final ForgeConfigSpec.BooleanValue RESPOND_TO_STATS_COMMAND = BUILDER
            .comment("Answer '/stats' in the Discord channel with player statistics and leaderboards.")
            .define("respondToStatsCommand", true);

    private static final ForgeConfigSpec.ConfigValue<String> OWNER_DISCORD_ID = BUILDER
            .comment("Discord user ID allowed to run /restart and /cmd operator console commands.")
            .define("ownerDiscordId", "462616453653331968");

    static
    {
        BUILDER.pop();
        BUILDER.comment("Minecraft -> Discord mentions. Lets players ping Discord users with @name in game.")
                .push("mentions");
    }

    private static final ForgeConfigSpec.BooleanValue ALLOW_MENTIONS = BUILDER
            .comment("Resolve '@name' in Minecraft chat into a real Discord ping.",
                     "OFF by default. Requires the privileged Server Members Intent:",
                     "Developer Portal > Bot > Privileged Gateway Intents > Server Members Intent.",
                     "If that intent is not granted the bot will refuse to connect at all.",
                     "Note this gives every player on the server a way to notify people who are not playing.")
            .define("allowMentions", false);

    private static final ForgeConfigSpec.BooleanValue ALLOW_EVERYONE_MENTION = BUILDER
            .comment("Allow players to trigger @everyone and @here from Minecraft chat.",
                     "Gated separately from allowMentions because it is far noisier. Keep it off",
                     "unless you trust everyone who can type in game.")
            .define("allowEveryoneMention", false);

    private static final ForgeConfigSpec.IntValue MAX_MENTIONS_PER_MESSAGE = BUILDER
            .comment("Mentions past this count in a single message are left as plain text.")
            .defineInRange("maxMentionsPerMessage", 3, 0, 10);

    private static final ForgeConfigSpec.IntValue MENTION_COOLDOWN_SECONDS = BUILDER
            .comment("Minimum seconds between one player's pings. Their message still relays,",
                     "but with mentions left as plain text. Set 0 to disable the cooldown.")
            .defineInRange("mentionCooldownSeconds", 10, 0, 3600);

    static
    {
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static String webhookUrl = "";
    public static String username = "Minecraft Server";
    public static String avatarUrl = "";
    public static boolean announceJoin = true;
    public static boolean announceLeave = true;
    public static boolean announceChat = true;
    public static boolean announceDeath = true;
    public static boolean announceCommands = true;
    public static boolean announceAdvancements = true;
    public static boolean announceServerStart = true;
    public static boolean announceServerStop = true;
    public static boolean useRichEmbeds = true;
    public static boolean sanitizeMarkdown = false;
    public static boolean suppressMentions = false;
    public static boolean maskUrlInStatus = false;
    public static boolean allowCustomWebhookHost = false;
    public static boolean inboundEnabled = false;
    public static String botToken = "";
    public static String channelId = "";
    public static int maxRelayedLength = 256;
    public static boolean relayBotMessages = false;
    public static boolean relayAttachments = true;
    public static boolean respondToPlayersCommand = true;
    public static boolean respondToStatsCommand = true;
    public static String ownerDiscordId = "462616453653331968";
    public static boolean allowMentions = false;
    public static boolean allowEveryoneMention = false;
    public static int maxMentionsPerMessage = 3;
    public static int mentionCooldownSeconds = 10;

    private DiscordConfig()
    {
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        if (event.getConfig().getSpec() != SPEC)
        {
            return;
        }
        refresh();
    }

    private static void refresh()
    {
        enabled = ENABLED.get();
        webhookUrl = safe(WEBHOOK_URL.get());
        username = safe(USERNAME.get());
        avatarUrl = safe(AVATAR_URL.get());
        announceJoin = ANNOUNCE_JOIN.get();
        announceLeave = ANNOUNCE_LEAVE.get();
        announceChat = ANNOUNCE_CHAT.get();
        announceDeath = ANNOUNCE_DEATH.get();
        announceCommands = ANNOUNCE_COMMANDS.get();
        announceAdvancements = ANNOUNCE_ADVANCEMENTS.get();
        announceServerStart = ANNOUNCE_SERVER_START.get();
        announceServerStop = ANNOUNCE_SERVER_STOP.get();
        useRichEmbeds = USE_RICH_EMBEDS.get();
        sanitizeMarkdown = SANITIZE_MARKDOWN.get();
        suppressMentions = SUPPRESS_MENTIONS.get();
        maskUrlInStatus = MASK_URL_IN_STATUS.get();
        allowCustomWebhookHost = ALLOW_CUSTOM_WEBHOOK_HOST.get();
        inboundEnabled = INBOUND_ENABLED.get();
        botToken = safe(BOT_TOKEN.get());
        channelId = safe(CHANNEL_ID.get());
        maxRelayedLength = MAX_RELAYED_LENGTH.get();
        relayBotMessages = RELAY_BOT_MESSAGES.get();
        relayAttachments = RELAY_ATTACHMENTS.get();
        respondToPlayersCommand = RESPOND_TO_PLAYERS_COMMAND.get();
        respondToStatsCommand = RESPOND_TO_STATS_COMMAND.get();
        ownerDiscordId = safe(OWNER_DISCORD_ID.get());
        if (ownerDiscordId.isBlank())
        {
            ownerDiscordId = "462616453653331968";
        }
        allowMentions = ALLOW_MENTIONS.get();
        allowEveryoneMention = ALLOW_EVERYONE_MENTION.get();
        maxMentionsPerMessage = MAX_MENTIONS_PER_MESSAGE.get();
        mentionCooldownSeconds = MENTION_COOLDOWN_SECONDS.get();
    }

    private static String safe(final String value)
    {
        return value == null ? "" : value.trim();
    }

    /* ------------------------------------------------------------------ */
    /* Mutation helpers used by the command handlers.                      */
    /* ------------------------------------------------------------------ */

    public static boolean setEnabled(final boolean value)
    {
        return apply(() -> ENABLED.set(value));
    }

    public static boolean setWebhookUrl(final String value)
    {
        return apply(() -> WEBHOOK_URL.set(safe(value)));
    }

    public static boolean setUsername(final String value)
    {
        return apply(() -> USERNAME.set(safe(value)));
    }

    public static boolean setAvatarUrl(final String value)
    {
        return apply(() -> AVATAR_URL.set(safe(value)));
    }

    public static boolean setAnnounce(final String key, final boolean value)
    {
        if (key == null)
        {
            return false;
        }
        switch (key.toLowerCase(Locale.ROOT))
        {
            case "join":
                return apply(() -> ANNOUNCE_JOIN.set(value));
            case "leave":
                return apply(() -> ANNOUNCE_LEAVE.set(value));
            case "chat":
                return apply(() -> ANNOUNCE_CHAT.set(value));
            case "death":
                return apply(() -> ANNOUNCE_DEATH.set(value));
            case "commands":
            case "command":
                return apply(() -> ANNOUNCE_COMMANDS.set(value));
            case "advancements":
            case "advancement":
            case "achievements":
            case "achievement":
                return apply(() -> ANNOUNCE_ADVANCEMENTS.set(value));
            case "server":
            case "lifecycle":
                final boolean a = apply(() -> ANNOUNCE_SERVER_START.set(value));
                final boolean b = apply(() -> ANNOUNCE_SERVER_STOP.set(value));
                return a && b;
            case "start":
                return apply(() -> ANNOUNCE_SERVER_START.set(value));
            case "stop":
                return apply(() -> ANNOUNCE_SERVER_STOP.set(value));
            case "embeds":
            case "embed":
                return apply(() -> USE_RICH_EMBEDS.set(value));
            default:
                return false;
        }
    }

    public static boolean setSanitizeMarkdown(final boolean value)
    {
        return apply(() -> SANITIZE_MARKDOWN.set(value));
    }

    public static boolean setSuppressMentions(final boolean value)
    {
        return apply(() -> SUPPRESS_MENTIONS.set(value));
    }

    public static boolean setMaskUrlInStatus(final boolean value)
    {
        return apply(() -> MASK_URL_IN_STATUS.set(value));
    }

    public static boolean setAllowCustomWebhookHost(final boolean value)
    {
        return apply(() -> ALLOW_CUSTOM_WEBHOOK_HOST.set(value));
    }

    public static boolean setInboundEnabled(final boolean value)
    {
        return apply(() -> INBOUND_ENABLED.set(value));
    }

    public static boolean setBotToken(final String value)
    {
        return apply(() -> BOT_TOKEN.set(safe(value)));
    }

    public static boolean setChannelId(final String value)
    {
        return apply(() -> CHANNEL_ID.set(safe(value)));
    }

    public static boolean setAllowMentions(final boolean value)
    {
        return apply(() -> ALLOW_MENTIONS.set(value));
    }

    public static boolean setAllowEveryoneMention(final boolean value)
    {
        return apply(() -> ALLOW_EVERYONE_MENTION.set(value));
    }

    public static boolean setMaxMentionsPerMessage(final int value)
    {
        if (value < 0 || value > 10)
        {
            return false;
        }
        return apply(() -> MAX_MENTIONS_PER_MESSAGE.set(value));
    }

    public static boolean setMentionCooldownSeconds(final int value)
    {
        if (value < 0 || value > 3600)
        {
            return false;
        }
        return apply(() -> MENTION_COOLDOWN_SECONDS.set(value));
    }

    /**
     * @return a one-line human summary of the mention settings, shared by the
     *         in-game and Discord status commands
     */
    public static String mentionStatusText()
    {
        final StringBuilder text = new StringBuilder(160);
        text.append("allowMentions: ").append(allowMentions);
        if (suppressMentions)
        {
            text.append(" (overridden: suppressMentions is on, nothing will resolve)");
        }
        text.append('\n');
        text.append("allowEveryoneMention: ").append(allowEveryoneMention).append('\n');
        text.append("maxMentionsPerMessage: ").append(maxMentionsPerMessage).append('\n');
        text.append("mentionCooldownSeconds: ").append(mentionCooldownSeconds);
        return text.toString();
    }

    /**
     * Checks if the given Discord user ID matches the authorized owner ID.
     */
    public static boolean isOwner(final String discordUserId)
    {
        if (discordUserId == null || discordUserId.isBlank())
        {
            return false;
        }
        return discordUserId.trim().equals(ownerDiscordId);
    }

    private static boolean apply(final Runnable mutation)
    {
        if (!SPEC.isLoaded())
        {
            LOGGER.warn("Refusing to write config: the spec is not loaded yet.");
            return false;
        }
        try
        {
            mutation.run();
            SPEC.save();
            refresh();
            return true;
        }
        catch (final RuntimeException e)
        {
            LOGGER.error("Failed to write Discord Bridge config", e);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Validation and display helpers.                                     */
    /* ------------------------------------------------------------------ */

    /**
     * @return true if the value looks like a usable Discord webhook endpoint.
     */
    public static boolean isValidWebhookUrl(final String value)
    {
        if (value == null || value.isBlank())
        {
            return false;
        }
        final URI uri;
        try
        {
            uri = URI.create(value.trim());
        }
        catch (final IllegalArgumentException e)
        {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
        {
            return false;
        }
        if (allowCustomWebhookHost)
        {
            return true;
        }
        final String host = uri.getHost().toLowerCase(Locale.ROOT);
        final boolean discordHost = host.equals("discord.com")
                || host.equals("discordapp.com")
                || host.endsWith(".discord.com")
                || host.endsWith(".discordapp.com");
        final String path = uri.getPath() == null ? "" : uri.getPath();
        return discordHost && path.startsWith("/api/webhooks/");
    }

    /**
     * @return the URL as it should be shown to a command sender.
     */
    public static String displayWebhookUrl()
    {
        if (webhookUrl.isBlank())
        {
            return "<not set>";
        }
        if (!maskUrlInStatus)
        {
            return webhookUrl;
        }
        return mask(webhookUrl);
    }

    /**
     * @return true if the inbound relay has everything it needs to run.
     */
    public static boolean isInboundConfigured()
    {
        return !botToken.isBlank() && isValidChannelId(channelId);
    }

    /**
     * Discord snowflakes are decimal ids, currently 17 to 20 digits long.
     */
    public static boolean isValidChannelId(final String value)
    {
        if (value == null)
        {
            return false;
        }
        final String trimmed = value.trim();
        if (trimmed.length() < 17 || trimmed.length() > 20)
        {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++)
        {
            if (!Character.isDigit(trimmed.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    private static String mask(final String url)
    {
        final int keep = 6;
        if (url.length() <= keep * 2)
        {
            return "***";
        }
        return url.substring(0, keep) + "..." + url.substring(url.length() - keep);
    }
}
