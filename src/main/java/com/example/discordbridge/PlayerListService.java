package com.example.discordbridge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the rich Discord embed for the online player list.
 *
 * <p>Reads live entity state, so it must be called on the server thread.</p>
 */
public final class PlayerListService
{
    /** Discord rejects embeds with more than 25 fields. */
    private static final int MAX_EMBED_FIELDS = 25;
    /** One field is reserved for the overflow notice. */
    private static final int MAX_PLAYER_FIELDS = MAX_EMBED_FIELDS - 1;

    private static final int COLOUR_ONLINE = 0x57F287;
    private static final int COLOUR_EMPTY = 0x95A5A6;
    private static final int COLOUR_ERROR = 0xED4245;

    private PlayerListService()
    {
    }

    /**
     * @param server the running server, may be null during shutdown
     * @return an embed listing everyone online with health and experience level
     */
    public static MessageEmbed buildPlayersEmbed(final MinecraftServer server)
    {
        final EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("\uD83D\uDC65 Online Players");
        embed.setTimestamp(Instant.now());

        if (server == null)
        {
            embed.setColor(COLOUR_ERROR);
            embed.setDescription("Server is not available.");
            return embed.build();
        }

        final int max = server.getPlayerList().getMaxPlayers();
        final List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        players.sort(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));

        if (players.isEmpty())
        {
            embed.setColor(COLOUR_EMPTY);
            embed.setDescription("Nobody is online right now.");
            embed.setFooter("0 / " + max + " online \u2022 Minecraft " + server.getServerVersion());
            return embed.build();
        }

        embed.setColor(COLOUR_ONLINE);
        embed.setThumbnail(DiscordWebhookSender.getPlayerAvatarUrl(players.get(0)));

        final int shown = Math.min(players.size(), MAX_PLAYER_FIELDS);
        for (int i = 0; i < shown; i++)
        {
            final ServerPlayer player = players.get(i);
            final String value = String.format(Locale.US, "\u2764\uFE0F %.1f / %.1f", player.getHealth(), player.getMaxHealth())
                    + "\n\u2728 Level " + player.experienceLevel;
            embed.addField(player.getGameProfile().getName(), value, true);
        }

        if (players.size() > shown)
        {
            embed.addField("\u2026and " + (players.size() - shown) + " more",
                    "Run `/players` in game to see the full list.", false);
        }

        embed.setFooter(players.size() + " / " + max + " online \u2022 Minecraft " + server.getServerVersion());
        return embed.build();
    }
}
