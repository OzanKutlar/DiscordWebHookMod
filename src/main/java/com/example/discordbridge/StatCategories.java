package com.example.discordbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.example.discordbridge.StatCategory.StatFormat.CENTIMETRES;
import static com.example.discordbridge.StatCategory.StatFormat.COUNT;
import static com.example.discordbridge.StatCategory.StatFormat.HALF_HEARTS;
import static com.example.discordbridge.StatCategory.StatFormat.RATIO;
import static com.example.discordbridge.StatCategory.StatFormat.TICKS;
import static com.example.discordbridge.StatCategory.StatGroup.BLOCKS;
import static com.example.discordbridge.StatCategory.StatGroup.COMBAT;
import static com.example.discordbridge.StatCategory.StatGroup.INTERACTIONS;
import static com.example.discordbridge.StatCategory.StatGroup.MOVEMENT;
import static com.example.discordbridge.StatCategory.StatGroup.OTHER;
import static com.example.discordbridge.StatCategory.StatGroup.TIME;
import static com.example.discordbridge.StatCategory.entry;
import static com.example.discordbridge.StatCategory.sectionTotal;
import static com.example.discordbridge.StatCategory.sumKeys;

/**
 * The registry of every statistic the mod can rank players by.
 *
 * <p>Order here is the order categories appear in overviews, tab completion and
 * player cards. To add a statistic, add one entry below; nothing else needs to
 * change.</p>
 */
public final class StatCategories
{
    private static final String CUSTOM = "minecraft:custom";
    private static final long TICKS_PER_HOUR = 72_000L;
    /** Below this much play time, deaths per hour is noise rather than a stat. */
    private static final long MIN_TICKS_FOR_RATE = TICKS_PER_HOUR / 10L;

    private static final List<StatCategory> ALL_CATEGORIES = buildAll();
    private static final Map<String, StatCategory> LOOKUP = buildLookup(ALL_CATEGORIES);
    private static final Map<StatCategory.StatGroup, List<StatCategory>> GROUPED = buildGrouped(ALL_CATEGORIES);

    private StatCategories()
    {
    }

    /**
     * @return every category, in display order. Immutable.
     */
    public static List<StatCategory> all()
    {
        return ALL_CATEGORIES;
    }

    /**
     * @return every category bucketed by group, in display order. Immutable.
     */
    public static Map<StatCategory.StatGroup, List<StatCategory>> byGroup()
    {
        return GROUPED;
    }

    /**
     * Case-insensitive lookup by id or alias.
     *
     * @return the category, or empty for unknown input. Never throws.
     */
    public static Optional<StatCategory> resolve(final String query)
    {
        if (query == null || query.isBlank())
        {
            return Optional.empty();
        }
        return Optional.ofNullable(LOOKUP.get(query.trim().toLowerCase(Locale.ROOT)));
    }

    private static Map<String, StatCategory> buildLookup(final List<StatCategory> categories)
    {
        final Map<String, StatCategory> map = new LinkedHashMap<>();
        for (final StatCategory category : categories)
        {
            map.put(category.id(), category);
            for (final String alias : category.aliases())
            {
                map.putIfAbsent(alias, category);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<StatCategory.StatGroup, List<StatCategory>> buildGrouped(final List<StatCategory> categories)
    {
        final Map<StatCategory.StatGroup, List<StatCategory>> map = new EnumMap<>(StatCategory.StatGroup.class);
        for (final StatCategory.StatGroup group : StatCategory.StatGroup.values())
        {
            map.put(group, new ArrayList<>());
        }
        for (final StatCategory category : categories)
        {
            map.get(category.group()).add(category);
        }
        for (final StatCategory.StatGroup group : StatCategory.StatGroup.values())
        {
            map.put(group, Collections.unmodifiableList(map.get(group)));
        }
        return Collections.unmodifiableMap(map);
    }

    private static StatCategory of(final String id, final Set<String> aliases, final String label,
                                   final String emoji, final StatCategory.StatGroup group,
                                   final StatCategory.StatSource source, final StatCategory.StatFormat format)
    {
        return new StatCategory(id, aliases, label, emoji, group, source, null, format);
    }

    private static StatCategory derived(final String id, final Set<String> aliases, final String label,
                                        final String emoji, final StatCategory.StatGroup group,
                                        final StatCategory.DerivedStat derived,
                                        final StatCategory.StatFormat format)
    {
        return new StatCategory(id, aliases, label, emoji, group, null, derived, format);
    }

    private static StatCategory external(final String id, final Set<String> aliases, final String label,
                                         final String emoji, final StatCategory.StatGroup group)
    {
        return new StatCategory(id, aliases, label, emoji, group, null, null, COUNT);
    }

    private static List<StatCategory> buildAll()
    {
        final List<StatCategory> list = new ArrayList<>();

        /* ---------------- Combat ---------------- */

        list.add(of("deaths", Set.of("death", "died", "dies"), "Deaths", "\uD83D\uDC80",
                COMBAT, entry(CUSTOM, "minecraft:deaths"), COUNT));
        list.add(of("mob_kills", Set.of("kills", "mobs", "mobkills"), "Mobs Killed", "\u2694\uFE0F",
                COMBAT, entry(CUSTOM, "minecraft:mob_kills"), COUNT));
        list.add(of("player_kills", Set.of("pvp", "playerkills"), "Player Kills", "\uD83D\uDDE1\uFE0F",
                COMBAT, entry(CUSTOM, "minecraft:player_kills"), COUNT));
        list.add(of("damage_taken", Set.of("damagetaken", "hurt", "punchingbag"), "Damage Taken", "\uD83E\uDD15",
                COMBAT, entry(CUSTOM, "minecraft:damage_taken"), HALF_HEARTS));
        list.add(of("damage_dealt", Set.of("damagedealt", "damage"), "Damage Dealt", "\uD83D\uDCA5",
                COMBAT, entry(CUSTOM, "minecraft:damage_dealt"), HALF_HEARTS));
        list.add(of("damage_blocked", Set.of("blocked", "shield", "turtle"), "Damage Blocked", "\uD83D\uDEE1\uFE0F",
                COMBAT, entry(CUSTOM, "minecraft:damage_blocked_by_shield"), HALF_HEARTS));

        /* ---------------- Blocks and items ---------------- */

        list.add(of("blocks_mined", Set.of("mined", "mining", "blocks"), "Blocks Mined", "\u26CF\uFE0F",
                BLOCKS, sectionTotal("minecraft:mined"), COUNT));
        list.add(of("items_crafted", Set.of("crafted", "crafting"), "Items Crafted", "\uD83D\uDD28",
                BLOCKS, sectionTotal("minecraft:crafted"), COUNT));
        list.add(of("items_used", Set.of("used", "usage"), "Items Used", "\uD83D\uDCE6",
                BLOCKS, sectionTotal("minecraft:used"), COUNT));
        list.add(of("tools_broken", Set.of("broken", "butterfingers"), "Tools Broken", "\uD83D\uDD27",
                BLOCKS, sectionTotal("minecraft:broken"), COUNT));
        list.add(of("items_enchanted", Set.of("enchanted", "enchants"), "Items Enchanted", "\u2728",
                BLOCKS, entry(CUSTOM, "minecraft:enchant_item"), COUNT));

        /* ---------------- Time ---------------- */

        list.add(of("play_time", Set.of("playtime", "time", "played"), "Time Played", "\u23F1\uFE0F",
                TIME, entry(CUSTOM, "minecraft:play_time"), TICKS));
        list.add(of("time_since_death", Set.of("survival", "streak", "sincedeath"), "Survival Streak", "\uD83C\uDF40",
                TIME, entry(CUSTOM, "minecraft:time_since_death"), TICKS));
        list.add(of("time_since_rest", Set.of("awake", "phantom", "sincerest"), "Time Since Sleep", "\uD83D\uDC7B",
                TIME, entry(CUSTOM, "minecraft:time_since_rest"), TICKS));
        list.add(of("sneak_time", Set.of("sneak", "crouch", "sneaky"), "Time Sneaking", "\uD83E\uDD77",
                TIME, entry(CUSTOM, "minecraft:sneak_time"), TICKS));

        /* ---------------- Movement ---------------- */

        list.add(of("blocks_walked", Set.of("walked", "walk", "distance"), "Distance Walked", "\uD83C\uDFC3",
                MOVEMENT, sumKeys(CUSTOM, "minecraft:walk_one_cm", "minecraft:sprint_one_cm", "minecraft:crouch_one_cm"),
                CENTIMETRES));
        list.add(of("fall_distance", Set.of("fall", "fell", "falls", "gravity"), "Distance Fallen", "\uD83E\uDE82",
                MOVEMENT, entry(CUSTOM, "minecraft:fall_one_cm"), CENTIMETRES));
        list.add(of("swim_distance", Set.of("swim", "swam"), "Distance Swum", "\uD83C\uDFCA",
                MOVEMENT, sumKeys(CUSTOM, "minecraft:swim_one_cm", "minecraft:walk_under_water_one_cm"),
                CENTIMETRES));
        list.add(of("climb_distance", Set.of("climb", "climbed", "ladder"), "Distance Climbed", "\uD83E\uDDD7",
                MOVEMENT, entry(CUSTOM, "minecraft:climb_one_cm"), CENTIMETRES));
        list.add(of("elytra_distance", Set.of("elytra", "fly", "flown", "aviate"), "Distance Flown", "\uD83E\uDD85",
                MOVEMENT, entry(CUSTOM, "minecraft:aviate_one_cm"), CENTIMETRES));
        list.add(of("boat_distance", Set.of("boat", "sailed"), "Distance by Boat", "\uD83D\uDEA3",
                MOVEMENT, entry(CUSTOM, "minecraft:boat_one_cm"), CENTIMETRES));
        list.add(of("horse_distance", Set.of("horse", "ridden"), "Distance by Horse", "\uD83D\uDC0E",
                MOVEMENT, entry(CUSTOM, "minecraft:horse_one_cm"), CENTIMETRES));
        list.add(of("pig_distance", Set.of("pig", "pigriding"), "Distance by Pig", "\uD83D\uDC16",
                MOVEMENT, entry(CUSTOM, "minecraft:pig_one_cm"), CENTIMETRES));
        list.add(of("minecart_distance", Set.of("minecart", "cart", "rails"), "Distance by Minecart", "\uD83D\uDE83",
                MOVEMENT, entry(CUSTOM, "minecraft:minecart_one_cm"), CENTIMETRES));

        /* ---------------- Interactions ---------------- */

        list.add(of("jumps", Set.of("jump", "jumped", "bunny"), "Times Jumped", "\uD83D\uDC07",
                INTERACTIONS, entry(CUSTOM, "minecraft:jump"), COUNT));
        list.add(of("leave_game", Set.of("quits", "ragequit", "leaves", "logouts"), "Times Left the Game", "\uD83D\uDEAA",
                INTERACTIONS, entry(CUSTOM, "minecraft:leave_game"), COUNT));
        list.add(of("chests_opened", Set.of("chests", "chest", "loot"), "Chests Opened", "\uD83E\uDDF3",
                INTERACTIONS, entry(CUSTOM, "minecraft:open_chest"), COUNT));
        list.add(of("bells_rung", Set.of("bells", "bell"), "Bells Rung", "\uD83D\uDD14",
                INTERACTIONS, entry(CUSTOM, "minecraft:bell_ring"), COUNT));
        list.add(of("flowers_potted", Set.of("flowers", "pots", "decorator"), "Flowers Potted", "\uD83C\uDF38",
                INTERACTIONS, entry(CUSTOM, "minecraft:pot_flower"), COUNT));
        list.add(of("cake_slices", Set.of("cake", "cakes"), "Cake Slices Eaten", "\uD83C\uDF70",
                INTERACTIONS, entry(CUSTOM, "minecraft:eat_cake_slice"), COUNT));
        list.add(of("fish_caught", Set.of("fish", "fishing", "fished"), "Fish Caught", "\uD83C\uDFA3",
                INTERACTIONS, entry(CUSTOM, "minecraft:fish_caught"), COUNT));
        list.add(of("villager_trades", Set.of("trades", "trading", "villagers"), "Villager Trades", "\uD83D\uDC9A",
                INTERACTIONS, entry(CUSTOM, "minecraft:traded_with_villager"), COUNT));
        list.add(of("animals_bred", Set.of("bred", "breeding", "animals"), "Animals Bred", "\uD83D\uDC2E",
                INTERACTIONS, entry(CUSTOM, "minecraft:animals_bred"), COUNT));
        list.add(of("raids_won", Set.of("raids", "raid"), "Raids Won", "\uD83C\uDFF4",
                INTERACTIONS, entry(CUSTOM, "minecraft:raid_win"), COUNT));
        list.add(of("nights_slept", Set.of("slept", "sleep", "beds"), "Nights Slept", "\uD83D\uDECF\uFE0F",
                INTERACTIONS, entry(CUSTOM, "minecraft:sleep_in_bed"), COUNT));

        /* ---------------- External and derived ---------------- */

        list.add(external("advancements", Set.of("achievements", "advancement", "achievement"),
                "Advancements", "\uD83C\uDFC6", OTHER));
        list.add(derived("deaths_per_hour", Set.of("dph", "clumsy", "deathrate"), "Deaths per Hour", "\uD83D\uDCC9",
                OTHER, StatCategories::deathsPerHour, RATIO));

        return Collections.unmodifiableList(list);
    }

    /**
     * Deaths per hour played, stored as hundredths so it fits a long.
     *
     * <p>Returns 0 below {@link #MIN_TICKS_FOR_RATE} so a player who died once in
     * their first minute does not permanently top the board.</p>
     */
    private static long deathsPerHour(final Map<String, Long> values)
    {
        if (values == null)
        {
            return 0L;
        }
        final long ticks = values.getOrDefault("play_time", 0L);
        final long deaths = values.getOrDefault("deaths", 0L);
        if (ticks < MIN_TICKS_FOR_RATE || deaths <= 0L)
        {
            return 0L;
        }
        final double hours = (double) ticks / (double) TICKS_PER_HOUR;
        return Math.round((deaths / hours) * 100.0);
    }
}
