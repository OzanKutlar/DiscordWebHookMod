package com.example.discordbridge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.management.ManagementFactory;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-game mirrors of the read-only Discord information commands.
 *
 * <p>Output is rendered as a box-drawn table and sent to the caller only, never
 * broadcast. All commands are available at permission level 0.</p>
 *
 * <p>{@code /restart} and {@code /cmd} are deliberately not mirrored: vanilla
 * already provides {@code /stop}, and an in-game console-execution command
 * would be a permission escalation.</p>
 */
@Mod.EventBusSubscriber(modid = DiscordBridge.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerInfoCommands
{
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final double MIN_TICK_MS = 50.0;
    private static final double MAX_TPS = 20.0;
    private static final int MAX_LEADERBOARD_ROWS = 15;

    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                            .map(player -> player.getGameProfile().getName()),
                    builder);

    /** Only offers categories somebody has actually scored on. */
    private static final SuggestionProvider<CommandSourceStack> STAT_CATEGORIES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    PlayerStatsService.activeCategoryIds(ctx.getSource().getServer()),
                    builder);

    private ServerInfoCommands()
    {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("players")
                .executes(ServerInfoCommands::players));

        event.getDispatcher().register(Commands.literal("status")
                .executes(ServerInfoCommands::status));

        event.getDispatcher().register(Commands.literal("stats")
                .executes(ServerInfoCommands::overview)
                .then(Commands.literal("player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .executes(ServerInfoCommands::playerStats)))
                .then(Commands.literal("category")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(STAT_CATEGORIES)
                                .executes(ServerInfoCommands::categoryStats))));
    }

    /* ------------------------------------------------------------------ */
    /* /players                                                            */
    /* ------------------------------------------------------------------ */

    private static int players(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();

        final List<ServerPlayer> online = new ArrayList<>(server.getPlayerList().getPlayers());
        online.sort(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("Player", "Health", "Level", "Ping")
                .emptyMessage("Nobody is online right now.");

        for (final ServerPlayer player : online)
        {
            table.row(
                    player.getGameProfile().getName(),
                    String.format(Locale.US, "%.1f / %.1f", player.getHealth(), player.getMaxHealth()),
                    Integer.toString(player.experienceLevel),
                    player.latency + " ms");
        }

        final Component title = Component.literal("Online players \u2014 " + online.size() + " / "
                        + server.getPlayerList().getMaxPlayers())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    /* ------------------------------------------------------------------ */
    /* /status                                                             */
    /* ------------------------------------------------------------------ */

    private static int status(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        final float avgTickTimeMs = server.getAverageTickTime();
        final double tps = Math.min(MAX_TPS, 1000.0 / Math.max(MIN_TICK_MS, avgTickTimeMs));

        final Runtime rt = Runtime.getRuntime();
        final long maxMem = rt.maxMemory() / BYTES_PER_MB;
        final long totalMem = rt.totalMemory() / BYTES_PER_MB;
        final long freeMem = rt.freeMemory() / BYTES_PER_MB;
        final long usedMem = totalMem - freeMem;

        final long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("Metric", "Value")
                .row("TPS", String.format(Locale.US, "%.1f", tps))
                .row("Tick time", String.format(Locale.US, "%.1f ms", avgTickTimeMs))
                .row("RAM used", fmt.format(usedMem) + " MB")
                .row("RAM allocated", fmt.format(totalMem) + " MB")
                .row("RAM max", fmt.format(maxMem) + " MB")
                .row("Uptime", DiscordAdminService.formatUptime(uptimeMs))
                .row("Players", server.getPlayerList().getPlayerCount()
                        + " / " + server.getPlayerList().getMaxPlayers())
                .row("Version", "Minecraft " + server.getServerVersion());

        final Component title = Component.literal("Server status")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    /* ------------------------------------------------------------------ */
    /* /stats                                                              */
    /* ------------------------------------------------------------------ */

    private static int overview(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();
        flushOnlineStats(server);

        final Set<String> active = PlayerStatsService.activeCategoryIds(server);

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("Group", "Category", "Leader", "Value")
                .emptyMessage("No player statistics recorded on this server yet.");

        for (final Map.Entry<StatCategory.StatGroup, List<StatCategory>> group
                : StatCategories.byGroup().entrySet())
        {
            for (final StatCategory category : group.getValue())
            {
                if (!active.contains(category.id()))
                {
                    continue;
                }
                final List<PlayerStatsService.PlayerStatsRecord> top =
                        PlayerStatsService.topFor(server, category, 1);
                if (top.isEmpty())
                {
                    continue;
                }
                final PlayerStatsService.PlayerStatsRecord leader = top.get(0);
                table.row(group.getKey().label(), category.label(), leader.name,
                        category.display(leader.value(category.id())));
            }
        }

        final Component title = Component.literal("Server leaderboards \u2014 every category")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        final Component hint = Component.literal("/stats category <name> for a full leaderboard")
                .withStyle(ChatFormatting.DARK_GRAY);

        reply(source, title, Component.empty().append(table.build()).append("\n").append(hint));
        return Command.SINGLE_SUCCESS;
    }

    private static int categoryStats(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();
        final String requested = StringArgumentType.getString(ctx, "name").trim();

        final Optional<StatCategory> resolved = StatCategories.resolve(requested);
        if (resolved.isEmpty())
        {
            source.sendFailure(Component.literal("Unknown statistic \"" + requested
                    + "\". Press tab after /stats category to see the available ones."));
            return 0;
        }

        final StatCategory category = resolved.get();
        flushOnlineStats(server);

        final List<PlayerStatsService.PlayerStatsRecord> top =
                PlayerStatsService.topFor(server, category, MAX_LEADERBOARD_ROWS);

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("#", "Player", "Value")
                .emptyMessage("Nobody has recorded anything for \"" + category.id() + "\" yet.");

        for (int i = 0; i < top.size(); i++)
        {
            final PlayerStatsService.PlayerStatsRecord record = top.get(i);
            table.row(Integer.toString(i + 1), record.name, category.display(record.value(category.id())));
        }

        final Component title = Component.literal(category.label() + " \u2014 leaderboard")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    private static int playerStats(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String requested = StringArgumentType.getString(ctx, "name").trim();
        final MinecraftServer server = source.getServer();
        flushOnlineStats(server);

        final PlayerStatsService.PlayerStatsRecord record =
                PlayerStatsService.collectStatsFor(server, requested);

        if (record == null)
        {
            source.sendFailure(Component.literal("No statistics found for \"" + requested
                    + "\". Check the spelling \u2014 the player must have joined this server before."));
            return 0;
        }

        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        final ServerPlayer online = server.getPlayerList().getPlayer(record.uuid);

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("Stat", "Value")
                .row("Status", online != null ? "Online" : "Offline");

        if (online != null)
        {
            table.row("Health", String.format(Locale.US, "%.1f / %.1f", online.getHealth(), online.getMaxHealth()));
            table.row("Experience", "Level " + online.experienceLevel
                    + " (" + fmt.format(online.totalExperience) + " XP)");
        }

        if (record.nemesis != null && !record.nemesis.isBlank())
        {
            table.row("Nemesis", record.nemesis);
        }

        for (final StatCategory category : StatCategories.all())
        {
            final long value = record.value(category.id());
            if (value != 0L)
            {
                table.row(category.label(), category.display(value));
            }
        }

        final Component title = Component.literal("Statistics \u2014 " + record.name)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Flushes online players' stats to disk so the numbers we read are current,
     * then drops the cached snapshot.
     */
    private static void flushOnlineStats(final MinecraftServer server)
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
                // A single failed flush just means slightly stale numbers.
            }
        }
        PlayerStatsService.invalidate();
    }

    /**
     * Sends the title and table to the caller only. {@code false} keeps the
     * output out of the operator broadcast.
     */
    private static void reply(final CommandSourceStack source, final Component title, final Component body)
    {
        final Component out = Component.empty().append(title).append("\n").append(body);
        source.sendSuccess(() -> out, false);
    }
}
