package com.example.discordbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One leaderboard-able statistic.
 *
 * <p>A category knows three things: how to pull its number out of a player's
 * stats JSON, how to render that number, and what to call itself. Everything
 * else in the stats system is driven off this, so adding a new statistic is a
 * single entry in {@link StatCategories} rather than a change spread across the
 * loader, the embeds and the commands.</p>
 *
 * <p>Exactly one of {@code source} or {@code derived} is normally set:</p>
 * <ul>
 *   <li>{@code source} reads directly from the {@code stats} object of a
 *       player's {@code stats/&lt;uuid&gt;.json}.</li>
 *   <li>{@code derived} is computed after all direct sources have been read,
 *       from the values map of the record being built.</li>
 *   <li>Both null means the value is supplied externally by the loader, which
 *       is how the advancement count (read from a different directory) works.</li>
 * </ul>
 */
public record StatCategory(String id,
                           Set<String> aliases,
                           String label,
                           String emoji,
                           StatCategory.StatSource source,
                           StatCategory.DerivedStat derived,
                           StatCategory.StatFormat format,
                           boolean inOverview)
{
    public StatCategory
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("A stat category needs an id.");
        }
        if (format == null)
        {
            throw new IllegalArgumentException("A stat category needs a format: " + id);
        }
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
        label = (label == null || label.isBlank()) ? id : label;
        emoji = emoji == null ? "" : emoji;
    }

    public boolean isDerived()
    {
        return derived != null;
    }

    /**
     * @return true if neither a source nor a derivation is set, meaning the
     *         loader fills this value in itself
     */
    public boolean isExternal()
    {
        return source == null && derived == null;
    }

    /**
     * @return the label with its emoji prefix, for embed field titles
     */
    public String heading()
    {
        return emoji.isEmpty() ? label : emoji + " " + label;
    }

    /**
     * @return true if the given user input names this category by id or alias
     */
    public boolean matches(final String query)
    {
        if (query == null || query.isBlank())
        {
            return false;
        }
        final String normalized = query.trim().toLowerCase(Locale.ROOT);
        return id.equals(normalized) || aliases.contains(normalized);
    }

    public String display(final long value)
    {
        return format.apply(value);
    }

    @FunctionalInterface
    public interface StatSource
    {
        /**
         * @param stats the {@code stats} object from a player's stats file, never null
         * @return the raw value, or 0 if this player has no entry for it
         */
        long read(JsonObject stats);
    }

    @FunctionalInterface
    public interface DerivedStat
    {
        /**
         * @param values every directly-read value for this player, keyed by category id
         * @return the computed value
         */
        long compute(Map<String, Long> values);
    }

    /**
     * How a raw stored value is turned into display text.
     */
    public enum StatFormat
    {
        /** A plain count, thousands-separated. */
        COUNT,
        /** Game ticks, rendered as a duration. */
        TICKS,
        /** Centimetres as vanilla stores them, rendered as blocks and kilometres. */
        CENTIMETRES,
        /** Damage in tenths of a health point, as vanilla stores it. */
        HALF_HEARTS,
        /** A ratio stored multiplied by 100. */
        RATIO;

        public String apply(final long value)
        {
            switch (this)
            {
                case TICKS:
                    return PlayerStatsService.formatPlayTime(value);
                case CENTIMETRES:
                    return PlayerStatsService.formatBlocks(value / 100L);
                case HALF_HEARTS:
                    return String.format(Locale.US, "%.1f HP", value / 10.0);
                case RATIO:
                    return String.format(Locale.US, "%.2f", value / 100.0);
                case COUNT:
                default:
                    return NumberFormat.getNumberInstance(Locale.US).format(value);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Source helpers.                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Reads a single key, e.g. {@code minecraft:custom} / {@code minecraft:deaths}.
     */
    public static StatSource entry(final String section, final String key)
    {
        return stats -> readLong(child(stats, section), key);
    }

    /**
     * Sums several keys in one section, e.g. walking plus sprinting plus crouching.
     */
    public static StatSource sumKeys(final String section, final String... keys)
    {
        return stats ->
        {
            final JsonObject obj = child(stats, section);
            if (obj == null || keys == null)
            {
                return 0L;
            }
            long total = 0L;
            for (final String key : keys)
            {
                total += readLong(obj, key);
            }
            return total;
        };
    }

    /**
     * Sums every entry in a section, e.g. all mined blocks or all broken tools.
     */
    public static StatSource sectionTotal(final String section)
    {
        return stats ->
        {
            final JsonObject obj = child(stats, section);
            if (obj == null)
            {
                return 0L;
            }
            long total = 0L;
            for (final Map.Entry<String, JsonElement> entry : obj.entrySet())
            {
                final JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive())
                {
                    continue;
                }
                try
                {
                    total += value.getAsLong();
                }
                catch (final RuntimeException ignored)
                {
                    // A single malformed entry must not void the whole section.
                }
            }
            return total;
        };
    }

    static JsonObject child(final JsonObject parent, final String key)
    {
        if (parent == null || key == null)
        {
            return null;
        }
        final JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static long readLong(final JsonObject obj, final String key)
    {
        if (obj == null || key == null)
        {
            return 0L;
        }
        final JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive())
        {
            return 0L;
        }
        try
        {
            return element.getAsLong();
        }
        catch (final RuntimeException e)
        {
            return 0L;
        }
    }
}
