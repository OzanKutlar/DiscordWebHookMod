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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * In-game mirrors of the read-only Discord information commands.
 *
 * <p>Output is rendered as a box-drawn table and sent to the caller only, never
 * broadcast. All three commands are available at permission level 0.</p>
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

    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                            .map(player -> player.getGameProfile().getName()),
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
                .executes(ServerInfoCommands::leaderboards)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYERS)
                        .executes(ServerInfoCommands::playerStats)));
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

    private static int leaderboards(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        final Map<UUID, PlayerStatsService.PlayerStatsRecord> all =
                PlayerStatsService.collectAllStats(source.getServer());
        final Collection<PlayerStatsService.PlayerStatsRecord> records = all.values();

        final ChatTableBuilder table = new ChatTableBuilder()
                .header("Category", "Leader", "Value")
                .emptyMessage("No player statistics recorded on this server yet.");

        addLeader(table, records, "Mobs killed",
                Comparator.comparingLong(r -> r.mobKills),
                r -> fmt.format(r.mobKills));
        addLeader(table, records, "Blocks mined",
                Comparator.comparingLong(r -> r.blocksMined),
                r -> fmt.format(r.blocksMined));
        addLeader(table, records, "Time played",
                Comparator.comparingLong(r -> r.playTimeTicks),
                r -> PlayerStatsService.formatPlayTime(r.playTimeTicks));
        addLeader(table, records, "Blocks walked",
                Comparator.comparingLong(r -> r.blocksWalked),
                r -> fmt.format(r.blocksWalked));
        addLeader(table, records, "Achievements",
                Comparator.comparingInt(r -> r.achievements),
                r -> Integer.toString(r.achievements));

        final Component title = Component.literal("Server leaderboards")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    private static int playerStats(final CommandContext<CommandSourceStack> ctx)
    {
        final CommandSourceStack source = ctx.getSource();
        final String requested = StringArgumentType.getString(ctx, "player").trim();
        final MinecraftServer server = source.getServer();

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

        table.row("Time played", PlayerStatsService.formatPlayTime(record.playTimeTicks));
        table.row("Mobs killed", fmt.format(record.mobKills));
        table.row("Blocks mined", fmt.format(record.blocksMined));
        table.row("Blocks walked", fmt.format(record.blocksWalked));
        table.row("Achievements", Integer.toString(record.achievements));

        final Component title = Component.literal("Statistics \u2014 " + record.name)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

        reply(source, title, table.build());
        return Command.SINGLE_SUCCESS;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static void addLeader(final ChatTableBuilder table,
                                  final Collection<PlayerStatsService.PlayerStatsRecord> records,
                                  final String label,
                                  final Comparator<PlayerStatsService.PlayerStatsRecord> order,
                                  final Function<PlayerStatsService.PlayerStatsRecord, String> value)
    {
        final PlayerStatsService.PlayerStatsRecord leader = records.stream().max(order).orElse(null);
        if (leader == null || leader.name == null || leader.name.isBlank())
        {
            return;
        }
        table.row(label, leader.name, value.apply(leader));
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
