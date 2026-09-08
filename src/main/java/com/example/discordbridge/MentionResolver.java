package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns {@code @name} in Minecraft chat into a real Discord ping.
 *
 * <p>A single {@link #scan} produces the segments that both the outbound Discord
 * text and the in-game highlight are built from, so what gets pinged and what
 * turns aqua in chat can never disagree.</p>
 *
 * <p>Matching runs against a snapshot of the guild member list rather than a
 * live gateway query, because chat relay happens on the webhook worker thread
 * and must never block on network I/O.</p>
 *
 * <p>Nothing here resolves unless {@link DiscordConfig#allowMentions} is on and
 * {@link DiscordConfig#suppressMentions} is off. The safe setting always wins.</p>
 */
public final class MentionResolver
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest display name we will try to match, so a long message cannot blow up the scan. */
    private static final int MAX_NAME_LENGTH = 32;
    private static final String EVERYONE = "everyone";
    private static final String HERE = "here";

    /** Lowercased display name to Discord user id. Replaced wholesale, never mutated. */
    private static volatile Map<String, String> directory = Map.of();
    private static final Map<UUID, Long> LAST_MENTION = new ConcurrentHashMap<>();

    private MentionResolver()
    {
    }

    /**
     * One piece of a scanned message: either plain text, or a resolved mention.
     */
    public record Segment(String text, String userId, boolean everyone)
    {
        public boolean isMention()
        {
            return userId != null || everyone;
        }
    }

    /**
     * @param text    the message rewritten for Discord
     * @param userIds the ids that may be pinged, for the allowed_mentions whitelist
     * @param everyone whether an @everyone or @here was authorised
     */
    public record Result(String text, Set<String> userIds, boolean everyone)
    {
        public static Result plain(final String text)
        {
            return new Result(text == null ? "" : text, Set.of(), false);
        }

        public boolean hasMentions()
        {
            return !userIds.isEmpty() || everyone;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Directory maintenance.                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Rebuilds the name to id snapshot from JDA's member cache.
     *
     * <p>Safe to call from a gateway thread. Does nothing if mentions are off,
     * since the member cache will not be populated in that case.</p>
     */
    public static void refresh(final JDA jda)
    {
        if (jda == null || !DiscordConfig.allowMentions)
        {
            directory = Map.of();
            return;
        }
        final Map<String, String> next = new HashMap<>();
        try
        {
            for (final Guild guild : jda.getGuilds())
            {
                for (final Member member : guild.getMemberCache())
                {
                    if (member.getUser().isBot())
                    {
                        continue;
                    }
                    final String id = member.getId();
                    putName(next, member.getNickname(), id);
                    putName(next, member.getUser().getGlobalName(), id);
                    putName(next, member.getUser().getName(), id);
                    putName(next, member.getEffectiveName(), id);
                }
            }
        }
        catch (final RuntimeException e)
        {
            LOGGER.warn("Could not refresh the Discord mention directory: {}", e.getMessage());
            return;
        }
        directory = Collections.unmodifiableMap(next);
        LOGGER.debug("Mention directory refreshed with {} names.", next.size());
    }

    private static void putName(final Map<String, String> map, final String name, final String id)
    {
        if (name == null || name.isBlank())
        {
            return;
        }
        map.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), id);
    }

    public static void clear()
    {
        directory = Map.of();
        LAST_MENTION.clear();
    }

    public static int directorySize()
    {
        return directory.size();
    }

    /* ------------------------------------------------------------------ */
    /* Public entry points.                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves mentions on behalf of a player, applying their cooldown.
     *
     * <p>A player still inside their cooldown gets their message relayed with
     * mentions left as plain text rather than being blocked outright.</p>
     */
    public static Result resolveFor(final ServerPlayer player, final String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return Result.plain("");
        }
        if (!enabled())
        {
            return Result.plain(raw);
        }
        final List<Segment> segments = scan(raw);
        if (!containsMention(segments))
        {
            return Result.plain(raw);
        }
        if (player != null && !claimCooldown(player.getUUID()))
        {
            return Result.plain(raw);
        }
        return toResult(segments);
    }

    /**
     * Resolves without any cooldown, for callers that are not player chat.
     */
    public static Result resolve(final String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return Result.plain("");
        }
        if (!enabled())
        {
            return Result.plain(raw);
        }
        return toResult(scan(raw));
    }

    /**
     * Renders the message for in-game chat, highlighting mentions that resolved.
     *
     * @param base the style for ordinary text
     */
    public static MutableComponent decorate(final String raw, final ChatFormatting base)
    {
        final MutableComponent out = Component.empty();
        if (raw == null || raw.isEmpty())
        {
            return out;
        }
        if (!enabled())
        {
            return out.append(Component.literal(raw).withStyle(base));
        }
        for (final Segment segment : scan(raw))
        {
            if (segment.isMention())
            {
                out.append(Component.literal(segment.text()).withStyle(ChatFormatting.AQUA));
            }
            else
            {
                out.append(Component.literal(segment.text()).withStyle(base));
            }
        }
        return out;
    }

    private static boolean enabled()
    {
        // suppressMentions is the safety switch and always wins.
        return DiscordConfig.allowMentions && !DiscordConfig.suppressMentions;
    }

    private static boolean containsMention(final List<Segment> segments)
    {
        for (final Segment segment : segments)
        {
            if (segment.isMention())
            {
                return true;
            }
        }
        return false;
    }

    private static Result toResult(final List<Segment> segments)
    {
        final StringBuilder text = new StringBuilder();
        final Set<String> ids = new LinkedHashSet<>();
        boolean everyone = false;

        for (final Segment segment : segments)
        {
            if (segment.userId() != null)
            {
                text.append("<@").append(segment.userId()).append('>');
                ids.add(segment.userId());
            }
            else if (segment.everyone())
            {
                text.append(segment.text());
                everyone = true;
            }
            else
            {
                text.append(segment.text());
            }
        }
        return new Result(text.toString(), Collections.unmodifiableSet(ids), everyone);
    }

    /**
     * Per-player rate limit. Returns true and records the attempt if allowed.
     */
    private static boolean claimCooldown(final UUID uuid)
    {
        final int seconds = DiscordConfig.mentionCooldownSeconds;
        if (seconds <= 0)
        {
            return true;
        }
        final long now = System.currentTimeMillis();
        final Long previous = LAST_MENTION.get(uuid);
        if (previous != null && now - previous < seconds * 1000L)
        {
            return false;
        }
        LAST_MENTION.put(uuid, now);
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Scanning.                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Splits the message into literal and mention segments.
     *
     * <p>Anything that does not resolve is emitted byte-identical to the input,
     * so an email address or a stray {@code @} is never mangled.</p>
     */
    private static List<Segment> scan(final String raw)
    {
        final List<Segment> segments = new ArrayList<>();
        final Map<String, String> names = directory;
        final int max = Math.max(0, DiscordConfig.maxMentionsPerMessage);

        final StringBuilder literal = new StringBuilder();
        int resolved = 0;
        int i = 0;

        while (i < raw.length())
        {
            final char c = raw.charAt(i);
            if (c != '@' || !isMentionBoundary(raw, i) || resolved >= max)
            {
                literal.append(c);
                i++;
                continue;
            }

            final Match match = matchAt(raw, i + 1, names);
            if (match == null)
            {
                literal.append(c);
                i++;
                continue;
            }

            if (literal.length() > 0)
            {
                segments.add(new Segment(literal.toString(), null, false));
                literal.setLength(0);
            }
            segments.add(new Segment(raw.substring(i, i + 1 + match.consumed()), match.userId(), match.everyone()));
            resolved++;
            i += 1 + match.consumed();
        }

        if (literal.length() > 0)
        {
            segments.add(new Segment(literal.toString(), null, false));
        }
        return segments;
    }

    /**
     * An {@code @} only starts a mention at the beginning of a word, so
     * {@code steve@example.com} is left alone.
     */
    private static boolean isMentionBoundary(final String raw, final int index)
    {
        if (index == 0)
        {
            return true;
        }
        final char previous = raw.charAt(index - 1);
        return !Character.isLetterOrDigit(previous) && previous != '_' && previous != '.';
    }

    private record Match(String userId, boolean everyone, int consumed)
    {
    }

    /**
     * Tries the quoted form, then progressively shorter bare names so the
     * longest match wins.
     *
     * @param from the index just past the {@code @}
     */
    private static Match matchAt(final String raw, final int from, final Map<String, String> names)
    {
        if (from >= raw.length())
        {
            return null;
        }

        // Quoted form: @"First Last"
        if (raw.charAt(from) == '"')
        {
            final int close = raw.indexOf('"', from + 1);
            if (close > from + 1 && close - from <= MAX_NAME_LENGTH + 2)
            {
                final String candidate = raw.substring(from + 1, close);
                final String id = lookup(candidate, names);
                if (id != null)
                {
                    return new Match(id, false, close - from + 1);
                }
            }
            return null;
        }

        final int limit = Math.min(raw.length(), from + MAX_NAME_LENGTH);
        for (int end = limit; end > from; end--)
        {
            final String candidate = raw.substring(from, end);
            if (candidate.isBlank())
            {
                continue;
            }

            final String lower = candidate.toLowerCase(Locale.ROOT);
            if (EVERYONE.equals(lower) || HERE.equals(lower))
            {
                if (DiscordConfig.allowEveryoneMention)
                {
                    return new Match(null, true, candidate.length());
                }
                continue;
            }

            final String id = lookup(candidate, names);
            if (id != null)
            {
                return new Match(id, false, candidate.length());
            }
        }
        return null;
    }

    /**
     * Looks a candidate up directly and with underscores treated as spaces, so
     * {@code @First_Last} finds a member called "First Last".
     */
    private static String lookup(final String candidate, final Map<String, String> names)
    {
        final String lower = candidate.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty())
        {
            return null;
        }
        final String direct = names.get(lower);
        if (direct != null)
        {
            return direct;
        }
        return names.get(lower.replace('_', ' '));
    }
}
