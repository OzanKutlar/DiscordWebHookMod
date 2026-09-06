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
import java.util.*;
import java.util.stream.Stream;

/**
 * Collects and formats player statistics and achievements from server files and active sessions.
 */
public final class PlayerStatsService
{
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class PlayerStatsRecord
    {
        public UUID uuid;
        public String name;
        public long mobKills = 0;
        public long blocksMined = 0;
        public long playTimeTicks = 0;
        public long blocksWalked = 0;
        public int achievements = 0;
    }

    private PlayerStatsService()
    {
    }

    /**
     * Builds a Discord embed displaying the server-wide leaderboards and current online player health/EXP.
     */
    public static MessageEmbed buildLeaderboardsEmbed(final MinecraftServer server)
    {
        final Map<UUID, PlayerStatsRecord> allStats = loadAllPlayerStats(server);
        final List<ServerPlayer> onlinePlayers = server.getPlayerList().getPlayers();

        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDCCA Minecraft Server Statistics & Leaderboards");
        embed.setColor(0xF1C40F); // Gold
        embed.setTimestamp(Instant.now());

        if (allStats.isEmpty() && onlinePlayers.isEmpty())
        {
            embed.setDescription("No player statistics recorded on this server yet.");
            return embed.build();
        }

        // Determine leaders
        final PlayerStatsRecord topKills = allStats.values().stream()
                .max(Comparator.comparingLong(p -> p.mobKills)).orElse(null);
        final PlayerStatsRecord topMined = allStats.values().stream()
                .max(Comparator.comparingLong(p -> p.blocksMined)).orElse(null);
        final PlayerStatsRecord topTime = allStats.values().stream()
                .max(Comparator.comparingLong(p -> p.playTimeTicks)).orElse(null);
        final PlayerStatsRecord topWalked = allStats.values().stream()
                .max(Comparator.comparingLong(p -> p.blocksWalked)).orElse(null);
        final PlayerStatsRecord topAchievements = allStats.values().stream()
                .max(Comparator.comparingInt(p -> p.achievements)).orElse(null);

        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        embed.addField("\u2694\uFE0F Most Mobs Killed",
                formatLeader(topKills, topKills != null ? fmt.format(topKills.mobKills) + " mobs" : "0"), true);

        embed.addField("\u26CF\uFE0F Most Blocks Mined",
                formatLeader(topMined, topMined != null ? fmt.format(topMined.blocksMined) + " blocks" : "0"), true);

        embed.addField("\u23F1\uFE0F Most Time Played",
                formatLeader(topTime, topTime != null ? formatPlayTime(topTime.playTimeTicks) : "0m"), true);

        embed.addField("\uD83C\uDFC3 Most Blocks Walked",
                formatLeader(topWalked, topWalked != null ? formatBlocks(topWalked.blocksWalked) : "0 blocks"), true);

        embed.addField("\uD83C\uDFC6 Most Achievements",
                formatLeader(topAchievements, topAchievements != null ? topAchievements.achievements + " completed" : "0"), true);

        // Highest Level currently
        final ServerPlayer highestLevelOnline = onlinePlayers.stream()
                .max(Comparator.comparingInt(p -> p.experienceLevel)).orElse(null);
        if (highestLevelOnline != null)
        {
            embed.addField("\uD83C\uDF1F Highest Current Level",
                    "**" + highestLevelOnline.getGameProfile().getName() + "** \u2014 Level "
                            + highestLevelOnline.experienceLevel + " (" + fmt.format(highestLevelOnline.totalExperience) + " XP)", true);
        }
        else
        {
            embed.addField("\uD83C\uDF1F Highest Current Level", "*No players online to query live EXP*", true);
        }

        // Online player health and EXP status
        final StringBuilder onlineStatus = new StringBuilder();
        if (onlinePlayers.isEmpty())
        {
            onlineStatus.append("*No players currently online.*");
        }
        else
        {
            for (final ServerPlayer p : onlinePlayers)
            {
                final String name = p.getGameProfile().getName();
                final float hp = p.getHealth();
                final float maxHp = p.getMaxHealth();
                final int level = p.experienceLevel;
                final int totalXp = p.totalExperience;
                onlineStatus.append(String.format("\u2022 **%s**: %.1f/%.1f HP \u2764\uFE0F | Level %d (%s XP)\n",
                        name, hp, maxHp, level, fmt.format(totalXp)));
            }
        }

        embed.addField("\u2764\uFE0F Online Players Status (" + onlinePlayers.size() + "/"
                + server.getPlayerList().getMaxPlayers() + ")", onlineStatus.toString(), false);

        embed.setFooter("Tip: Use /stats player:<name> for individual stats \u2022 Lifetime EXP is not tracked across deaths in vanilla");
        return embed.build();
    }

    /**
     * Builds a Discord embed displaying statistics for a specific player.
     */
    public static MessageEmbed buildPlayerStatsEmbed(final MinecraftServer server, final String targetName)
    {
        final UUID uuid = resolvePlayerUuid(server, targetName);
        if (uuid == null)
        {
            final EmbedBuilder notFound = new EmbedBuilder();
            notFound.setTitle("Player Not Found");
            notFound.setColor(0xED4245); // Red
            notFound.setDescription("No statistics or player record found for **" + targetName + "**.\n"
                    + "Make sure the name is spelled correctly and the player has joined this server before.");
            return notFound.build();
        }

        final String resolvedName = resolvePlayerName(server, uuid, targetName);
        final Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        final Path advDir = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
        final PlayerStatsRecord record = loadSingleStats(statsDir, advDir, uuid, resolvedName);

        final ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(uuid);
        final boolean isOnline = onlinePlayer != null;
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDCCA Player Statistics: " + resolvedName);
        embed.setThumbnail("https://mc-heads.net/head/" + resolvedName);
        embed.setColor(isOnline ? 0x2ECC71 : 0x95A5A6); // Green if online, Gray if offline
        embed.setTimestamp(Instant.now());

        embed.addField("Status", isOnline ? "\uD83D\uDFE2 Online" : "\u26AA Offline", true);

        if (isOnline)
        {
            embed.addField("Current Health",
                    String.format("%.1f / %.1f HP \u2764\uFE0F", onlinePlayer.getHealth(), onlinePlayer.getMaxHealth()), true);
            embed.addField("Current Experience",
                    "Level " + onlinePlayer.experienceLevel + " (" + fmt.format(onlinePlayer.totalExperience) + " XP)", true);
        }

        embed.addField("\u23F1\uFE0F Time Played", formatPlayTime(record.playTimeTicks), true);
        embed.addField("\u2694\uFE0F Mobs Killed", fmt.format(record.mobKills), true);
        embed.addField("\u26CF\uFE0F Blocks Mined", fmt.format(record.blocksMined), true);
        embed.addField("\uD83C\uDFC3 Blocks Walked", formatBlocks(record.blocksWalked), true);
        embed.addField("\uD83C\uDFC6 Achievements Completed", record.achievements + " completed", true);

        embed.setFooter(isOnline ? "Player is currently in-game" : "Stats loaded from player records");
        return embed.build();
    }

    private static String formatLeader(final PlayerStatsRecord leader, final String statFormatted)
    {
        if (leader == null || leader.name == null)
        {
            return "*None*";
        }
        return "**" + leader.name + "** \u2014 " + statFormatted;
    }

    public static String formatPlayTime(final long ticks)
    {
        final long seconds = ticks / 20;
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
            return fmt.format(blocks) + " blocks (" + String.format("%.1f km", blocks / 1000.0) + ")";
        }
        return fmt.format(blocks) + " blocks";
    }

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
                final String fileName = path.getFileName().toString();
                final String uuidStr = fileName.substring(0, fileName.length() - 5);
                final UUID uuid;
                try
                {
                    uuid = UUID.fromString(uuidStr);
                }
                catch (final IllegalArgumentException e)
                {
                    continue;
                }

                final String name = resolvePlayerName(server, uuid, null);
                final PlayerStatsRecord record = loadSingleStats(statsDir, advDir, uuid, name);
                map.put(uuid, record);
            }
        }
        catch (final Exception e)
        {
            LOGGER.error("Failed to read player stats directory: {}", e.getMessage());
        }

        return map;
    }

    private static PlayerStatsRecord loadSingleStats(final Path statsDir, final Path advDir, final UUID uuid, final String name)
    {
        final PlayerStatsRecord record = new PlayerStatsRecord();
        record.uuid = uuid;
        record.name = name;

        final Path statsFile = statsDir.resolve(uuid.toString() + ".json");
        if (Files.exists(statsFile))
        {
            try (BufferedReader reader = Files.newBufferedReader(statsFile, StandardCharsets.UTF_8))
            {
                final JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject())
                {
                    final JsonObject rootObj = root.getAsJsonObject();
                    if (rootObj.has("stats") && rootObj.get("stats").isJsonObject())
                    {
                        final JsonObject stats = rootObj.getAsJsonObject("stats");
                        // Custom stats
                        if (stats.has("minecraft:custom") && stats.get("minecraft:custom").isJsonObject())
                        {
                            final JsonObject custom = stats.getAsJsonObject("minecraft:custom");
                            if (custom.has("minecraft:mob_kills"))
                            {
                                record.mobKills = custom.get("minecraft:mob_kills").getAsLong();
                            }
                            if (custom.has("minecraft:play_time"))
                            {
                                record.playTimeTicks = custom.get("minecraft:play_time").getAsLong();
                            }
                            final long walk = custom.has("minecraft:walk_one_cm") ? custom.get("minecraft:walk_one_cm").getAsLong() : 0;
                            final long sprint = custom.has("minecraft:sprint_one_cm") ? custom.get("minecraft:sprint_one_cm").getAsLong() : 0;
                            final long crouch = custom.has("minecraft:crouch_one_cm") ? custom.get("minecraft:crouch_one_cm").getAsLong() : 0;
                            record.blocksWalked = (walk + sprint + crouch) / 100;
                        }

                        // Mined blocks
                        if (stats.has("minecraft:mined") && stats.get("minecraft:mined").isJsonObject())
                        {
                            final JsonObject mined = stats.getAsJsonObject("minecraft:mined");
                            long totalMined = 0;
                            for (final Map.Entry<String, JsonElement> entry : mined.entrySet())
                            {
                                if (entry.getValue().isJsonPrimitive())
                                {
                                    totalMined += entry.getValue().getAsLong();
                                }
                            }
                            record.blocksMined = totalMined;
                        }
                    }
                }
            }
            catch (final Exception e)
            {
                LOGGER.warn("Could not read stats for {}: {}", uuid, e.getMessage());
            }
        }

        record.achievements = countAdvancements(advDir, uuid);
        return record;
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
            final JsonObject obj = root.getAsJsonObject();
            for (final Map.Entry<String, JsonElement> entry : obj.entrySet())
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
        // Check online players
        final ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online != null)
        {
            return online.getUUID();
        }
        // Check usercache
        final Optional<GameProfile> cached = server.getProfileCache().get(targetName);
        if (cached.isPresent())
        {
            return cached.get().getId();
        }
        // Check stats directory matches
        final Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (Files.isDirectory(statsDir))
        {
            try (Stream<Path> stream = Files.list(statsDir))
            {
                for (final Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                {
                    final String fileName = path.getFileName().toString();
                    final String uuidStr = fileName.substring(0, fileName.length() - 5);
                    try
                    {
                        final UUID uuid = UUID.fromString(uuidStr);
                        final String name = resolvePlayerName(server, uuid, null);
                        if (targetName.equalsIgnoreCase(name))
                        {
                            return uuid;
                        }
                    }
                    catch (final IllegalArgumentException ignored)
                    {
                    }
                }
            }
            catch (final Exception ignored)
            {
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
