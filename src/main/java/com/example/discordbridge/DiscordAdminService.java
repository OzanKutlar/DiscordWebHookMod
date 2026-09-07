package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles server performance monitoring, server restarts, and operator console command execution.
 */
public final class DiscordAdminService
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_DISCORD_OUTPUT_LENGTH = 1900;

    private DiscordAdminService()
    {
    }

    /**
     * Builds a Discord embed showing server performance, memory, TPS, and uptime.
     */
    public static MessageEmbed buildStatusEmbed(final MinecraftServer server)
    {
        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDDA5\uFE0F Server Status");
        embed.setColor(0x3498DB); // Blue
        embed.setTimestamp(Instant.now());

        // TPS & Tick time calculation
        final float avgTickTimeMs = server.getAverageTickTime();
        final double tps = Math.min(20.0, 1000.0 / Math.max(50.0, avgTickTimeMs));
        final String tpsStatus = String.format("%.1f TPS (%.1f ms/tick)", tps, avgTickTimeMs);

        // Memory usage
        final Runtime rt = Runtime.getRuntime();
        final long maxMem = rt.maxMemory() / (1024 * 1024);
        final long totalMem = rt.totalMemory() / (1024 * 1024);
        final long freeMem = rt.freeMemory() / (1024 * 1024);
        final long usedMem = totalMem - freeMem;
        final String memStatus = String.format("%d MB / %d MB (Max: %d MB)", usedMem, totalMem, maxMem);

        // Uptime
        final long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        final String uptimeStatus = formatUptime(uptimeMs);

        // Players
        final int online = server.getPlayerList().getPlayerCount();
        final int max = server.getPlayerList().getMaxPlayers();

        embed.addField("\uD83D\uDFE2 Status", "Online", true);
        embed.addField("\u26A1 Performance", tpsStatus, true);
        embed.addField("\uD83E\uDEE0 Memory (RAM)", memStatus, true);
        embed.addField("\u23F1\uFE0F Uptime", uptimeStatus, true);
        embed.addField("\uD83D\uDC65 Players", online + " / " + max, true);
        embed.addField("\uD83D\uDCE6 Version", "Minecraft " + server.getServerVersion(), true);

        embed.setFooter("Server Health Monitor");
        return embed.build();
    }

    /**
     * Executes a command on the main server thread with OP Level 4 permissions,
     * capturing all output lines and returning them via callback.
     */
    public static void executeConsoleCommand(final MinecraftServer server, final String command, final Consumer<String> callback)
    {
        if (server == null)
        {
            callback.accept(":x: Server is currently unavailable.");
            return;
        }

        server.execute(() -> {
            final List<String> outputs = new ArrayList<>();

            final CommandSource captureSource = new CommandSource()
            {
                @Override
                public void sendSystemMessage(final Component component)
                {
                    outputs.add(component.getString());
                }

                @Override
                public boolean acceptsSuccess()
                {
                    return true;
                }

                @Override
                public boolean acceptsFailure()
                {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins()
                {
                    return false;
                }
            };

            final CommandSourceStack sourceStack = new CommandSourceStack(
                    captureSource,
                    Vec3.ZERO,
                    Vec2.ZERO,
                    server.overworld(),
                    4, // OP Level 4
                    "DiscordAdmin",
                    Component.literal("DiscordAdmin"),
                    server,
                    null
            );

            try
            {
                final String cleanCmd = command.startsWith("/") ? command.substring(1) : command;
                LOGGER.info("Executing command from Discord Admin: /{}", cleanCmd);
                server.getCommands().performPrefixedCommand(sourceStack, cleanCmd);
            }
            catch (final Exception e)
            {
                outputs.add("Error executing command: " + e.getMessage());
            }

            final String formattedReply;
            if (outputs.isEmpty())
            {
                formattedReply = "```\n(Command executed successfully with no output)\n```";
            }
            else
            {
                final StringBuilder sb = new StringBuilder("```\n");
                for (final String line : outputs)
                {
                    if (sb.length() + line.length() > MAX_DISCORD_OUTPUT_LENGTH)
                    {
                        sb.append("... [output truncated]\n");
                        break;
                    }
                    sb.append(line).append('\n');
                }
                sb.append("```");
                formattedReply = sb.toString();
            }

            callback.accept(formattedReply);
        });
    }

    /**
     * Broadcasts restart warning in-game and initiates graceful server halt.
     */
    public static void restartServer(final MinecraftServer server, final String requestedBy)
    {
        if (server == null)
        {
            return;
        }

        server.execute(() -> {
            LOGGER.warn("Server restart initiated via Discord by {}", requestedBy);
            final Component alert = Component.literal("[Server] Restart initiated by Discord Admin (" + requestedBy + ")...")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            server.getPlayerList().broadcastSystemMessage(alert, false);

            // Clean halt: flushes worlds, saves player data, disconnects clients, exits cleanly.
            server.halt(false);
        });
    }

    /**
     * Formats a millisecond uptime as a compact human-readable duration.
     *
     * <p>Public so the in-game {@code /status} table renders it identically.</p>
     */
    public static String formatUptime(final long ms)
    {
        final long seconds = ms / 1000;
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
}
