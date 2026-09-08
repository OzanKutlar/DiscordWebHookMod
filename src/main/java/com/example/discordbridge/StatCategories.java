package com.example.discordbridge;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * @return the id of every category flagged for the default overview
     */
    public static List<StatCategory> overview()
    {
        final List<StatCategory> out = new ArrayList<>();
        for (final StatCategory category : ALL_CATEGORIES)
        {
            if (category.inOverview())
            {
                out.add(category);
            }
        }
        return out;
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

    private static StatCategory of(final String id, final Set<String> aliases, final String label,
                                   final String emoji, final StatCategory.StatSource source,
                                   final StatCategory.StatFormat format, final boolean inOverview)
    {
        return new StatCategory(id, aliases, label, emoji, source, null, format, inOverview);
    }

    private static StatCategory derived(final String id, final Set<String> aliases, final String label,
                                        final String emoji, final StatCategory.DerivedStat derived,
                                        final StatCategory.StatFormat format)
    {
        return new StatCategory(id, aliases, label, emoji, null, derived, format, false);
    }

    private static StatCategory external(final String id, final Set<String> aliases, final String label,
                                         final String emoji, final boolean inOverview)
    {
        return new StatCategory(id, aliases, label, emoji, null, null, COUNT, inOverview);
    }

    private static List<StatCategory> buildAll()
    {
        final List<StatCategory> list = new ArrayList<>();

        /* ---------------- Combat ---------------- */

        list.add(of("deaths", Set.of("death", "died", "dies"), "Deaths", "\uD83D\uDC80",
                entry(CUSTOM, "minecraft:deaths"), COUNT, true));
        list.add(of("mob_kills", Set.of("kills", "mobs", "mobkills"), "Mobs Killed", "\u2694\uFE0F",
                entry(CUSTOM, "minecraft:mob_kills"), COUNT, true));
        list.add(of("player_kills", Set.of("pvp", "playerkills"), "Player Kills", "\uD83D\uDDE1\uFE0F",
                entry(CUSTOM, "minecraft:player_kills"), COUNT, false));
        list.add(of("damage_taken", Set.of("damagetaken", "hurt", "punchingbag"), "Damage Taken", "\uD83E\uDD15",
                entry(CUSTOM, "minecraft:damage_taken"), HALF_HEARTS, true));
        list.add(of("damage_dealt", Set.of("damagedealt", "damage"), "Damage Dealt", "\uD83D\uDCA5",
                entry(CUSTOM, "minecraft:damage_dealt"), HALF_HEARTS, false));
        list.add(of("damage_blocked", Set.of("blocked", "shield", "turtle"), "Damage Blocked", "\uD83D\uDEE1\uFE0F",
                entry(CUSTOM, "minecraft:damage_blocked_by_shield"), HALF_HEARTS, false));

        /* ---------------- Blocks and items ---------------- */

        list.add(of("blocks_mined", Set.of("mined", "mining", "blocks"), "Blocks Mined", "\u26CF\uFE0F",
                sectionTotal("minecraft:mined"), COUNT, true));
        list.add(of("items_crafted", Set.of("crafted", "crafting"), "Items Crafted", "\uD83D\uDD28",
                sectionTotal("minecraft:crafted"), COUNT, false));
        list.add(of("items_used", Set.of("used", "usage"), "Items Used", "\uD83D\uDCE6",
                sectionTotal("minecraft:used"), COUNT, false));
        list.add(of("tools_broken", Set.of("broken", "butterfingers"), "Tools Broken", "\uD83D\uDD27",
                sectionTotal("minecraft:broken"), COUNT, false));
        list.add(of("items_enchanted", Set.of("enchanted", "enchants"), "Items Enchanted", "\u2728",
                entry(CUSTOM, "minecraft:enchant_item"), COUNT, false));

        /* ---------------- Time ---------------- */

        list.add(of("play_time", Set.of("playtime", "time", "played"), "Time Played", "\u23F1\uFE0F",
                entry(CUSTOM, "minecraft:play_time"), TICKS, true));
        list.add(of("time_since_death", Set.of("survival", "streak", "sincedeath"), "Survival Streak", "\uD83C\uDF40",
                entry(CUSTOM, "minecraft:time_since_death"), TICKS, false));
        list.add(of("time_since_rest", Set.of("awake", "phantom", "sincerest"), "Time Since Sleep", "\uD83D\uDC7B",
                entry(CUSTOM, "minecraft:time_since_rest"), TICKS, false));
        list.add(of("sneak_time", Set.of("sneak", "crouch", "sneaky"), "Time Sneaking", "\uD83E\uDD77",
                entry(CUSTOM, "minecraft:sneak_time"), TICKS, false));

        /* ---------------- Movement ---------------- */

        list.add(of("blocks_walked", Set.of("walked", "walk", "distance"), "Distance Walked", "\uD83C\uDFC3",
                sumKeys(CUSTOM, "minecraft:walk_one_cm", "minecraft:sprint_one_cm", "minecraft:crouch_one_cm"),
                CENTIMETRES, true));
        list.add(of("fall_distance", Set.of("fall", "fell", "falls", "gravity"), "Distance Fallen", "\uD83E\uDE82",
                entry(CUSTOM, "minecraft:fall_one_cm"), CENTIMETRES, false));
        list.add(of("swim_distance", Set.of("swim", "swam"), "Distance Swum", "\uD83C\uDFCA",
                sumKeys(CUSTOM, "minecraft:swim_one_cm", "minecraft:walk_under_water_one_cm"),
                CENTIMETRES, false));
        list.add(of("climb_distance", Set.of("climb", "climbed", "ladder"), "Distance Climbed", "\uD83E\uDDD7",
                entry(CUSTOM, "minecraft:climb_one_cm"), CENTIMETRES, false));
        list.add(of("elytra_distance", Set.of("elytra", "fly", "flown", "aviate"), "Distance Flown", "\uD83E\uDD85",
                entry(CUSTOM, "minecraft:aviate_one_cm"), CENTIMETRES, false));
        list.add(of("boat_distance", Set.of("boat", "sailed"), "Distance by Boat", "\uD83D\uDEA3",
                entry(CUSTOM, "minecraft:boat_one_cm"), CENTIMETRES, false));
        list.add(of("horse_distance", Set.of("horse", "ridden"), "Distance by Horse", "\uD83D\uDC0E",
                entry(CUSTOM, "minecraft:horse_one_cm"), CENTIMETRES, false));
        list.add(of("pig_distance", Set.of("pig", "pigriding"), "Distance by Pig", "\uD83D\uDC16",
                entry(CUSTOM, "minecraft:pig_one_cm"), CENTIMETRES, false));
        list.add(of("minecart_distance", Set.of("minecart", "cart", "rails"), "Distance by Minecart", "\uD83D\uDE83",
                entry(CUSTOM, "minecraft:minecart_one_cm"), CENTIMETRES, false));

        /* ---------------- Interactions ---------------- */

        list.add(of("jumps", Set.of("jump", "jumped", "bunny"), "Times Jumped", "\uD83D\uDC07",
                entry(CUSTOM, "minecraft:jump"), COUNT, true));
        list.add(of("leave_game", Set.of("quits", "ragequit", "leaves", "logouts"), "Times Left the Game", "\uD83D\uDEAA",
                entry(CUSTOM, "minecraft:leave_game"), COUNT, false));
        list.add(of("chests_opened", Set.of("chests", "chest", "loot"), "Chests Opened", "\uD83E\uDDF3",
                entry(CUSTOM, "minecraft:open_chest"), COUNT, false));
        list.add(of("bells_rung", Set.of("bells", "bell"), "Bells Rung", "\uD83D\uDD14",
                entry(CUSTOM, "minecraft:bell_ring"), COUNT, false));
        list.add(of("flowers_potted", Set.of("flowers", "pots", "decorator"), "Flowers Potted", "\uD83C\uDF38",
                entry(CUSTOM, "minecraft:pot_flower"), COUNT, false));
        list.add(of("cake_slices", Set.of("cake", "cakes"), "Cake Slices Eaten", "\uD83C\uDF70",
                entry(CUSTOM, "minecraft:eat_cake_slice"), COUNT, false));
        list.add(of("fish_caught", Set.of("fish", "fishing", "fished"), "Fish Caught", "\uD83C\uDFA3",
                entry(CUSTOM, "minecraft:fish_caught"), COUNT, false));
        list.add(of("villager_trades", Set.of("trades", "trading", "villagers"), "Villager Trades", "\uD83D\uDC9A",
                entry(CUSTOM, "minecraft:traded_with_villager"), COUNT, false));
        list.add(of("animals_bred", Set.of("bred", "breeding", "animals"), "Animals Bred", "\uD83D\uDC2E",
                entry(CUSTOM, "minecraft:animals_bred"), COUNT, false));
        list.add(of("raids_won", Set.of("raids", "raid"), "Raids Won", "\uD83C\uDFF4",
                entry(CUSTOM, "minecraft:raid_win"), COUNT, false));
        list.add(of("nights_slept", Set.of("slept", "sleep", "beds"), "Nights Slept", "\uD83D\uDECF\uFE0F",
                entry(CUSTOM, "minecraft:sleep_in_bed"), COUNT, false));

        /* ---------------- External and derived ---------------- */

        list.add(external("advancements", Set.of("achievements", "advancement", "achievement"),
                "Advancements", "\uD83C\uDFC6", true));
        list.add(derived("deaths_per_hour", Set.of("dph", "clumsy", "deathrate"), "Deaths per Hour", "\uD83D\uDCC9",
                StatCategories::deathsPerHour, RATIO));

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
