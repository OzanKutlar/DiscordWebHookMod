package com.example.discordbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Collects and formats player statistics from the world save.
 *
 * <p>Every statistic is described by a {@link StatCategory} in
 * {@link StatCategories}; this class only knows how to read files, apply those
 * categories, and render the results. Both the Discord embeds and the in-game
 * tables go through here so the two can never disagree.</p>
 *
 * <p>Results are cached for {@value #CACHE_TTL_MS} ms because a full collection
 * parses every recorded player's JSON. Call {@link #invalidate()} after anything
 * that writes stats to disk.</p>
 */
public final class PlayerStatsService
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long CACHE_TTL_MS = 30_000L;
    /** Discord rejects embeds with more than 25 fields. */
    private static final int MAX_EMBED_FIELDS = 25;
    /** Discord rejects field values longer than this. */
    private static final int MAX_FIELD_VALUE_LENGTH = 1024;
    private static final int MAX_LEADERBOARD_ROWS = 15;
    private static final String KILLED_BY = "minecraft:killed_by";

    private static final int COLOUR_LEADERBOARD = 0xF1C40F;
    private static final int COLOUR_ONLINE = 0x2ECC71;
    private static final int COLOUR_OFFLINE = 0x95A5A6;
    private static final int COLOUR_ERROR = 0xED4245;

    private static volatile Snapshot snapshot;

    /**
     * One player's statistics, keyed by {@link StatCategory#id()}.
     */
    public static final class PlayerStatsRecord
    {
        public UUID uuid;
        public String name;
        /** Most frequent entry in {@code minecraft:killed_by}, or null. */
        public String nemesis;

        private final Map<String, Long> values = new HashMap<>();

        public long value(final String categoryId)
        {
            if (categoryId == null)
            {
                return 0L;
            }
            final Long stored = values.get(categoryId);
            return stored == null ? 0L : stored;
        }

        void put(final String categoryId, final long value)
        {
            if (categoryId != null)
            {
                values.put(categoryId, value);
            }
        }

        Map<String, Long> rawValues()
        {
            return values;
        }

        public long mobKills()
        {
            return value("mob_kills");
        }

        public long blocksMined()
        {
            return value("blocks_mined");
        }

        public long playTimeTicks()
        {
            return value("play_time");
        }

        /** @return distance walked in blocks, not the stored centimetres */
        public long blocksWalked()
        {
            return value("blocks_walked") / 100L;
        }

        public int achievements()
        {
            return (int) value("advancements");
        }
    }

    private record Snapshot(Map<UUID, PlayerStatsRecord> records, Set<String> active, long takenAt)
    {
    }

    private PlayerStatsService()
    {
    }

    /* ------------------------------------------------------------------ */
    /* Collection and caching.                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Reads every recorded player's statistics from the world save.
     *
     * @return a map keyed by player UUID, empty if the stats directory is missing
     */
    public static Map<UUID, PlayerStatsRecord> collectAllStats(final MinecraftServer server)
    {
        return snapshot(server).records();
    }

    /**
     * Drops the cached snapshot. Call this after stats are flushed to disk or a
     * player disconnects, so the next query sees fresh numbers.
     */
    public static void invalidate()
    {
        snapshot = null;
    }

    /**
     * @return the ids of categories at least one player has a non-zero value for
     */
    public static Set<String> activeCategoryIds(final MinecraftServer server)
    {
        return snapshot(server).active();
    }

    /**
     * Cheap variant for callers that must not touch disk, such as the Discord
     * autocomplete handler running on a gateway thread.
     *
     * @return the cached active set, or every known id if nothing is cached yet
     */
    public static Set<String> suggestibleCategoryIds()
    {
        final Snapshot current = snapshot;
        if (current != null && !current.active().isEmpty())
        {
            return current.active();
        }
        final Set<String> ids = new LinkedHashSet<>();
        for (final StatCategory category : StatCategories.all())
        {
            ids.add(category.id());
        }
        return Collections.unmodifiableSet(ids);
    }

    private static Snapshot snapshot(final MinecraftServer server)
    {
        if (server == null)
        {
            return new Snapshot(Map.of(), Set.of(), System.currentTimeMillis());
        }
        final long now = System.currentTimeMillis();
        final Snapshot current = snapshot;
        if (current != null && now - current.takenAt() < CACHE_TTL_MS)
        {
            return current;
        }
        final Map<UUID, PlayerStatsRecord> loaded = loadAllPlayerStats(server);
        final Snapshot fresh = new Snapshot(
                Collections.unmodifiableMap(loaded),
                computeActive(loaded.values()),
                now);
        snapshot = fresh;
        return fresh;
    }

    private static Set<String> computeActive(final Iterable<PlayerStatsRecord> records)
    {
        final Set<String> active = new LinkedHashSet<>();
        for (final StatCategory category : StatCategories.all())
        {
            for (final PlayerStatsRecord record : records)
            {
                if (record.value(category.id()) != 0L)
                {
                    active.add(category.id());
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(active);
    }

    /**
     * Resolves a player by name and reads their statistics.
     *
     * @return the record, or null if the name is not a known player
     */
    public static PlayerStatsRecord collectStatsFor(final MinecraftServer server, final String targetName)
    {
        if (server == null || targetName == null || targetName.isBlank())
        {
            return null;
        }
        final String trimmed = targetName.trim();
        final UUID uuid = resolvePlayerUuid(server, trimmed);
        if (uuid == null)
        {
            return null;
        }
        final String resolvedName = resolvePlayerName(server, uuid, trimmed);
        final Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        final Path advDir = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
        return loadSingleStats(statsDir, advDir, uuid, resolvedName);
    }

    /**
     * Ranks players by one category, highest first.
     *
     * <p>Zero-scoring players are dropped: a leaderboard padded with forty ties
     * on nothing is noise.</p>
     *
     * @param limit the maximum number of rows, clamped to at least 1
     */
    public static List<PlayerStatsRecord> topFor(final MinecraftServer server, final StatCategory category,
                                                 final int limit)
    {
        if (server == null || category == null)
        {
            return List.of();
        }
        final int cap = Math.max(1, limit);
        final List<PlayerStatsRecord> ranked = new ArrayList<>();
        for (final PlayerStatsRecord record : collectAllStats(server).values())
        {
            if (record.value(category.id()) > 0L && record.name != null && !record.name.isBlank())
            {
                ranked.add(record);
            }
        }
        ranked.sort(Comparator.comparingLong((PlayerStatsRecord r) -> r.value(category.id())).reversed()
                .thenComparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
        return ranked.size() <= cap ? ranked : new ArrayList<>(ranked.subList(0, cap));
    }

    /* ------------------------------------------------------------------ */
    /* Discord embeds.                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The overview: the leader in every category anyone has scored on.
     *
     * <p>One embed field per group rather than per category, because 37 separate
     * fields would blow straight past Discord's 25-field limit.</p>
     */
    public static MessageEmbed buildOverviewEmbed(final MinecraftServer server)
    {
        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDCCA Server Statistics \u2014 Hall of Fame");
        embed.setColor(COLOUR_LEADERBOARD);
        embed.setTimestamp(Instant.now());

        if (server == null)
        {
            embed.setColor(COLOUR_ERROR);
            embed.setDescription("Server is not available.");
            return embed.build();
        }

        final Map<UUID, PlayerStatsRecord> allStats = collectAllStats(server);
        final Set<String> active = activeCategoryIds(server);
        final List<ServerPlayer> onlinePlayers = server.getPlayerList().getPlayers();

        if (allStats.isEmpty() || active.isEmpty())
        {
            embed.setDescription("No player statistics recorded on this server yet.");
            return embed.build();
        }

        // One field per group is reserved for the online summary at the end.
        int fields = 0;
        for (final Map.Entry<StatCategory.StatGroup, List<StatCategory>> group : StatCategories.byGroup().entrySet())
        {
            if (fields >= MAX_EMBED_FIELDS - 1)
            {
                break;
            }
            final List<String> lines = groupLines(allStats.values(), active, group.getValue());
            if (lines.isEmpty())
            {
                continue;
            }
            fields += addWrappedField(embed, group.getKey().heading(), lines, MAX_EMBED_FIELDS - 1 - fields);
        }

        if (fields == 0)
        {
            embed.setDescription("No player statistics recorded on this server yet.");
            return embed.build();
        }

        embed.addField("\u2764\uFE0F Online (" + onlinePlayers.size() + "/"
                + server.getPlayerList().getMaxPlayers() + ")", onlineSummary(onlinePlayers), false);
        embed.setFooter("Use /stats category name:<category> for a full leaderboard, or /stats player name:<player>");
        return embed.build();
    }

    private static List<String> groupLines(final Iterable<PlayerStatsRecord> records, final Set<String> active,
                                           final List<StatCategory> categories)
    {
        final List<String> lines = new ArrayList<>();
        for (final StatCategory category : categories)
        {
            if (!active.contains(category.id()))
            {
                continue;
            }
            final PlayerStatsRecord leader = leaderOf(records, category);
            if (leader == null)
            {
                continue;
            }
            lines.add(category.label() + " \u2014 **" + leader.name + "** ("
                    + category.display(leader.value(category.id())) + ")");
        }
        return lines;
    }

    /**
     * Adds one field, spilling into continuation fields rather than truncating
     * when the lines exceed Discord's per-field character limit.
     *
     * @param budget the number of fields still available
     * @return how many fields were actually added
     */
    private static int addWrappedField(final EmbedBuilder embed, final String heading,
                                       final List<String> lines, final int budget)
    {
        if (budget <= 0 || lines.isEmpty())
        {
            return 0;
        }
        int used = 0;
        StringBuilder buffer = new StringBuilder(MAX_FIELD_VALUE_LENGTH);

        for (final String line : lines)
        {
            if (buffer.length() + line.length() + 1 > MAX_FIELD_VALUE_LENGTH)
            {
                if (used >= budget)
                {
                    return used;
                }
                embed.addField(used == 0 ? heading : heading + " (cont.)", buffer.toString(), false);
                used++;
                buffer = new StringBuilder(MAX_FIELD_VALUE_LENGTH);
            }
            buffer.append(line).append('\n');
        }

        if (buffer.length() > 0 && used < budget)
        {
            embed.addField(used == 0 ? heading : heading + " (cont.)", buffer.toString(), false);
            used++;
        }
        return used;
    }

    /**
     * The full ranked leaderboard for one category.
     */
    public static MessageEmbed buildCategoryLeaderboardEmbed(final MinecraftServer server, final StatCategory category)
    {
        final EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(COLOUR_LEADERBOARD);
        embed.setTimestamp(Instant.now());

        if (server == null || category == null)
        {
            embed.setTitle("Leaderboard");
            embed.setColor(COLOUR_ERROR);
            embed.setDescription("Server is not available.");
            return embed.build();
        }

        embed.setTitle(category.heading() + " \u2014 Leaderboard");

        final List<PlayerStatsRecord> top = topFor(server, category, MAX_LEADERBOARD_ROWS);
        if (top.isEmpty())
        {
            embed.setColor(COLOUR_OFFLINE);
            embed.setDescription("Nobody has recorded anything for this statistic yet.");
            embed.setFooter("Category: " + category.id());
            return embed.build();
        }

        final StringBuilder body = new StringBuilder(256);
        for (int i = 0; i < top.size(); i++)
        {
            final PlayerStatsRecord record = top.get(i);
            body.append(medal(i))
                    .append(" **").append(record.name).append("** \u2014 ")
                    .append(category.display(record.value(category.id())))
                    .append('\n');
        }

        embed.setDescription(body.toString());
        embed.setThumbnail("https://mc-heads.net/head/" + top.get(0).name);
        embed.setFooter("Category: " + category.id() + " \u2022 showing top " + top.size());
        return embed.build();
    }

    /**
     * One player's full card: every statistic they have a non-zero value for.
     */
    public static MessageEmbed buildPlayerStatsEmbed(final MinecraftServer server, final String targetName)
    {
        final PlayerStatsRecord record = collectStatsFor(server, targetName);
        if (record == null)
        {
            final EmbedBuilder notFound = new EmbedBuilder();
            notFound.setTitle("Player Not Found");
            notFound.setColor(COLOUR_ERROR);
            notFound.setDescription("No statistics or player record found for **" + targetName + "**.\n"
                    + "Make sure the name is spelled correctly and the player has joined this server before.");
            return notFound.build();
        }

        final ServerPlayer online = server.getPlayerList().getPlayer(record.uuid);
        final boolean isOnline = online != null;
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDCCA Player Statistics: " + record.name);
        embed.setThumbnail("https://mc-heads.net/head/" + record.name);
        embed.setColor(isOnline ? COLOUR_ONLINE : COLOUR_OFFLINE);
        embed.setTimestamp(Instant.now());

        int fields = 0;
        embed.addField("Status", isOnline ? "\uD83D\uDFE2 Online" : "\u26AA Offline", true);
        fields++;

        if (isOnline)
        {
            embed.addField("Current Health",
                    String.format(Locale.US, "%.1f / %.1f HP", online.getHealth(), online.getMaxHealth()), true);
            embed.addField("Current Experience",
                    "Level " + online.experienceLevel + " (" + fmt.format(online.totalExperience) + " XP)", true);
            fields += 2;
        }

        if (record.nemesis != null && !record.nemesis.isBlank())
        {
            embed.addField("\uD83E\uDD4A Nemesis", record.nemesis, true);
            fields++;
        }

        for (final StatCategory category : StatCategories.all())
        {
            if (fields >= MAX_EMBED_FIELDS)
            {
                break;
            }
            final long value = record.value(category.id());
            if (value == 0L)
            {
                continue;
            }
            embed.addField(category.heading(), category.display(value), true);
            fields++;
        }

        embed.setFooter(isOnline ? "Player is currently in-game" : "Stats loaded from player records");
        return embed.build();
    }

    private static PlayerStatsRecord leaderOf(final Iterable<PlayerStatsRecord> records, final StatCategory category)
    {
        PlayerStatsRecord best = null;
        for (final PlayerStatsRecord record : records)
        {
            if (record.name == null || record.name.isBlank() || record.value(category.id()) <= 0L)
            {
                continue;
            }
            if (best == null || record.value(category.id()) > best.value(category.id()))
            {
                best = record;
            }
        }
        return best;
    }

    private static String onlineSummary(final List<ServerPlayer> onlinePlayers)
    {
        if (onlinePlayers.isEmpty())
        {
            return "*No players currently online.*";
        }
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        final StringBuilder out = new StringBuilder(128);
        for (final ServerPlayer player : onlinePlayers)
        {
            out.append(String.format(Locale.US, "\u2022 **%s**: %.1f/%.1f HP | Level %d (%s XP)\n",
                    player.getGameProfile().getName(), player.getHealth(), player.getMaxHealth(),
                    player.experienceLevel, fmt.format(player.totalExperience)));
        }
        return out.toString();
    }

    private static String medal(final int index)
    {
        switch (index)
        {
            case 0:
                return "\uD83E\uDD47";
            case 1:
                return "\uD83E\uDD48";
            case 2:
                return "\uD83E\uDD49";
            default:
                return "`#" + (index + 1) + "`";
        }
    }

    /* ------------------------------------------------------------------ */
    /* Plain-text export for the /overview LLM prompt.                     */
    /* ------------------------------------------------------------------ */

    private static final int TEXT_LEADERBOARD_ROWS = 5;

    /**
     * Writes online players' stats to disk and drops the cached snapshot, so the
     * numbers read next are current. Must run on the server thread.
     */
    public static void flushOnlineStats(final MinecraftServer server)
    {
        if (server == null)
        {
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers())
        {
            try
            {
                player.getStats().save();
            }
            catch (final Exception e)
            {
                LOGGER.debug("Could not flush stats for {}: {}",
                        player.getGameProfile().getName(), e.getMessage());
            }
        }
        invalidate();
    }

    /**
     * The whole statistics picture as plain text, with no Discord markdown or emoji:
     * every category leader, the top few per category, and one card per player.
     *
     * <p>Reads the world save, so call it on the server thread like the embed builders.</p>
     */
    public static String buildStatsText(final MinecraftServer server)
    {
        if (server == null)
        {
            return "(statistics unavailable: the server is not running)";
        }
        final Map<UUID, PlayerStatsRecord> all = collectAllStats(server);
        final Set<String> active = activeCategoryIds(server);
        if (all.isEmpty() || active.isEmpty())
        {
            return "(no player statistics were recorded on this server)";
        }

        final StringBuilder out = new StringBuilder(4096);
        out.append("Generated: ").append(Instant.now()).append('\n');
        out.append("Survival Streak and Time Since Sleep are the values at that moment; everything else is a lifetime total.\n\n");
        appendLeaders(out, all.values(), active);
        appendLeaderboards(out, server, active);
        appendPlayerCards(out, all.values());
        return out.toString();
    }

    private static void appendLeaders(final StringBuilder out, final Iterable<PlayerStatsRecord> records,
                                      final Set<String> active)
    {
        out.append("-- Leader in every category anyone scored on --\n");
        for (final Map.Entry<StatCategory.StatGroup, List<StatCategory>> group : StatCategories.byGroup().entrySet())
        {
            final List<String> lines = new ArrayList<>();
            for (final StatCategory category : group.getValue())
            {
                if (!active.contains(category.id()))
                {
                    continue;
                }
                final PlayerStatsRecord leader = leaderOf(records, category);
                if (leader != null)
                {
                    lines.add("  " + category.label() + ": " + leader.name
                            + " (" + category.display(leader.value(category.id())) + ")");
                }
            }
            if (lines.isEmpty())
            {
                continue;
            }
            out.append('[').append(group.getKey().label()).append("]\n");
            for (final String line : lines)
            {
                out.append(line).append('\n');
            }
        }
    }

    private static void appendLeaderboards(final StringBuilder out, final MinecraftServer server,
                                           final Set<String> active)
    {
        out.append("\n-- Top ").append(TEXT_LEADERBOARD_ROWS).append(" in every category --\n");
        for (final StatCategory category : StatCategories.all())
        {
            if (!active.contains(category.id()))
            {
                continue;
            }
            final List<PlayerStatsRecord> top = topFor(server, category, TEXT_LEADERBOARD_ROWS);
            if (top.isEmpty())
            {
                continue;
            }
            out.append("  ").append(category.label()).append(": ");
            for (int i = 0; i < top.size(); i++)
            {
                final PlayerStatsRecord record = top.get(i);
                if (i > 0)
                {
                    out.append(", ");
                }
                out.append(i + 1).append(". ").append(record.name)
                        .append(" (").append(category.display(record.value(category.id()))).append(')');
            }
            out.append('\n');
        }
    }

    private static void appendPlayerCards(final StringBuilder out, final Iterable<PlayerStatsRecord> records)
    {
        final List<PlayerStatsRecord> sorted = new ArrayList<>();
        for (final PlayerStatsRecord record : records)
        {
            if (record.name != null && !record.name.isBlank())
            {
                sorted.add(record);
            }
        }
        sorted.sort(Comparator.comparing((PlayerStatsRecord r) -> r.name, String.CASE_INSENSITIVE_ORDER));

        out.append("\n-- Player cards (every non-zero statistic) --\n");
        for (final PlayerStatsRecord record : sorted)
        {
            final List<String> cells = new ArrayList<>();
            for (final StatCategory category : StatCategories.all())
            {
                final long value = record.value(category.id());
                if (value != 0L)
                {
                    cells.add(category.label() + " " + category.display(value));
                }
            }
            if (record.nemesis != null && !record.nemesis.isBlank())
            {
                cells.add("Nemesis " + record.nemesis);
            }
            out.append(record.name).append(": ")
                    .append(cells.isEmpty() ? "(nothing recorded)" : String.join(" | ", cells))
                    .append('\n');
        }
    }

    /* ------------------------------------------------------------------ */
    /* Formatting helpers, shared with the in-game tables.                 */
    /* ------------------------------------------------------------------ */

    public static String formatPlayTime(final long ticks)
    {
        final long seconds = Math.max(0L, ticks) / 20L;
        final long days = seconds / 86400;
        final long hours = (seconds % 86400) / 3600;
        final long minutes = (seconds % 3600) / 60;
        if (days > 0)
        {
            return String.format("%dd %dh %dm", days, hours, minutes);
        }
        if (hours > 0)
        {
            return String.format("%dh %dm", hours, minutes);
        }
        return String.format("%dm %ds", minutes, seconds % 60);
    }

    public static String formatBlocks(final long blocks)
    {
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        if (blocks >= 1000)
        {
            return fmt.format(blocks) + " blocks (" + String.format(Locale.US, "%.1f km", blocks / 1000.0) + ")";
        }
        return fmt.format(blocks) + " blocks";
    }

    /* ------------------------------------------------------------------ */
    /* File loading.                                                       */
    /* ------------------------------------------------------------------ */

    private static Map<UUID, PlayerStatsRecord> loadAllPlayerStats(final MinecraftServer server)
    {
        final Map<UUID, PlayerStatsRecord> map = new HashMap<>();
        final Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        final Path advDir = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);

        if (!Files.isDirectory(statsDir))
        {
            return map;
        }

        try (Stream<Path> stream = Files.list(statsDir))
        {
            final List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            for (final Path path : files)
            {
                final UUID uuid = uuidFromFileName(path.getFileName().toString());
                if (uuid == null)
                {
                    continue;
                }
                final String name = resolvePlayerName(server, uuid, null);
                map.put(uuid, loadSingleStats(statsDir, advDir, uuid, name));
            }
        }
        catch (final Exception e)
        {
            LOGGER.error("Failed to read player stats directory: {}", e.getMessage());
        }

        return map;
    }

    private static UUID uuidFromFileName(final String fileName)
    {
        if (fileName == null || fileName.length() <= 5)
        {
            return null;
        }
        try
        {
            return UUID.fromString(fileName.substring(0, fileName.length() - 5));
        }
        catch (final IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Parses one player's stats file once, then applies every registered category.
     */
    private static PlayerStatsRecord loadSingleStats(final Path statsDir, final Path advDir,
                                                     final UUID uuid, final String name)
    {
        final PlayerStatsRecord record = new PlayerStatsRecord();
        record.uuid = uuid;
        record.name = name;

        final JsonObject stats = readStatsObject(statsDir.resolve(uuid.toString() + ".json"), uuid);
        if (stats != null)
        {
            applyDirectSources(record, stats, uuid);
            record.nemesis = findNemesis(stats);
        }

        // Advancements live in a separate directory, so they are filled in here
        // rather than through a StatSource.
        record.put("advancements", countAdvancements(advDir, uuid));

        applyDerivedSources(record, uuid);
        return record;
    }

    private static void applyDirectSources(final PlayerStatsRecord record, final JsonObject stats, final UUID uuid)
    {
        for (final StatCategory category : StatCategories.all())
        {
            if (category.source() == null)
            {
                continue;
            }
            try
            {
                record.put(category.id(), category.source().read(stats));
            }
            catch (final RuntimeException e)
            {
                // One bad category must not void the whole record.
                LOGGER.warn("Could not read stat '{}' for {}: {}", category.id(), uuid, e.getMessage());
            }
        }
    }

    private static void applyDerivedSources(final PlayerStatsRecord record, final UUID uuid)
    {
        for (final StatCategory category : StatCategories.all())
        {
            if (!category.isDerived())
            {
                continue;
            }
            try
            {
                record.put(category.id(), category.derived().compute(record.rawValues()));
            }
            catch (final RuntimeException e)
            {
                LOGGER.warn("Could not derive stat '{}' for {}: {}", category.id(), uuid, e.getMessage());
            }
        }
    }

    private static JsonObject readStatsObject(final Path statsFile, final UUID uuid)
    {
        if (!Files.exists(statsFile))
        {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(statsFile, StandardCharsets.UTF_8))
        {
            final JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject())
            {
                return null;
            }
            return StatCategory.child(root.getAsJsonObject(), "stats");
        }
        catch (final Exception e)
        {
            LOGGER.warn("Could not read stats for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * @return the prettified name of whatever has killed this player most often
     */
    private static String findNemesis(final JsonObject stats)
    {
        final JsonObject killedBy = StatCategory.child(stats, KILLED_BY);
        if (killedBy == null)
        {
            return null;
        }
        String bestKey = null;
        long bestValue = 0L;
        for (final Map.Entry<String, JsonElement> entry : killedBy.entrySet())
        {
            final long value = StatCategory.readLong(killedBy, entry.getKey());
            if (value > bestValue)
            {
                bestValue = value;
                bestKey = entry.getKey();
            }
        }
        if (bestKey == null)
        {
            return null;
        }
        return prettifyEntityId(bestKey) + " (\u00D7" + bestValue + ")";
    }

    private static String prettifyEntityId(final String id)
    {
        final int colon = id.indexOf(':');
        final String bare = colon >= 0 ? id.substring(colon + 1) : id;
        final String[] parts = bare.split("_");
        final StringBuilder out = new StringBuilder(bare.length());
        for (final String part : parts)
        {
            if (part.isEmpty())
            {
                continue;
            }
            if (out.length() > 0)
            {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.length() == 0 ? bare : out.toString();
    }

    private static int countAdvancements(final Path advDir, final UUID uuid)
    {
        final Path file = advDir.resolve(uuid.toString() + ".json");
        if (!Files.exists(file))
        {
            return 0;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            final JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject())
            {
                return 0;
            }
            int count = 0;
            for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet())
            {
                final String key = entry.getKey();
                // Skip recipes and metadata
                if ("DataVersion".equals(key) || key.contains(":recipes/"))
                {
                    continue;
                }
                final JsonElement val = entry.getValue();
                if (val.isJsonObject())
                {
                    final JsonObject adv = val.getAsJsonObject();
                    if (adv.has("done") && adv.get("done").getAsBoolean())
                    {
                        count++;
                    }
                }
            }
            return count;
        }
        catch (final Exception e)
        {
            LOGGER.warn("Could not read achievements for {}: {}", uuid, e.getMessage());
            return 0;
        }
    }

    private static UUID resolvePlayerUuid(final MinecraftServer server, final String targetName)
    {
        final ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online != null)
        {
            return online.getUUID();
        }
        final Optional<GameProfile> cached = server.getProfileCache().get(targetName);
        if (cached.isPresent())
        {
            return cached.get().getId();
        }
        final Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (Files.isDirectory(statsDir))
        {
            try (Stream<Path> stream = Files.list(statsDir))
            {
                for (final Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                {
                    final UUID uuid = uuidFromFileName(path.getFileName().toString());
                    if (uuid == null)
                    {
                        continue;
                    }
                    if (targetName.equalsIgnoreCase(resolvePlayerName(server, uuid, null)))
                    {
                        return uuid;
                    }
                }
            }
            catch (final Exception e)
            {
                LOGGER.warn("Could not scan the stats directory for '{}': {}", targetName, e.getMessage());
            }
        }
        return null;
    }

    private static String resolvePlayerName(final MinecraftServer server, final UUID uuid, final String fallback)
    {
        final ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null)
        {
            return online.getGameProfile().getName();
        }
        final Optional<GameProfile> cached = server.getProfileCache().get(uuid);
        if (cached.isPresent() && cached.get().getName() != null && !cached.get().getName().isBlank())
        {
            return cached.get().getName();
        }
        if (fallback != null && !fallback.isBlank())
        {
            return fallback;
        }
        return uuid.toString().substring(0, 8);
    }
}
