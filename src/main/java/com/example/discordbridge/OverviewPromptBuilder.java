package com.example.discordbridge;

import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Assembles the end-of-season LLM prompt and splits it into uploadable parts.
 */
public final class OverviewPromptBuilder
{
    /** Kept well under Discord's 10 MB upload limit for servers without boosts. */
    public static final int MAX_PART_BYTES = 8 * 1024 * 1024;
    /** Room reserved in each part for the part markers. */
    private static final int MARKER_RESERVE_BYTES = 512;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String INSTRUCTIONS = """
            You are the official historian of a Minecraft SMP (survival multiplayer) server that is wrapping up.
            Below are the final server statistics and the complete log of the server's Discord bridge channel,
            which mirrors in-game chat, Discord chat, joins and leaves, deaths, advancements and commands.
            The players will read your recap together at the end of the season, so make it a joy to read.

            HOW TO READ THE LOG
            Each line looks like: [YYYY-MM-DD HH:MM] event    (all times are UTC)
              <Name> text            in-game chat
              [DC] <Name> text       chat typed in Discord
              (reply to Name)        a Discord reply to that person
              » Name ran /command    a command run in game
              * Name joined/left     a join or leave
              ☠ ...                  a player death (vanilla death message)
              ★ ...                  an advancement, goal or challenge
              • ...                  another server notice
              [SERVER] ...           server start, stop or console messages
              (×N)                   the same line repeated N times in a row
              " / "                  a line break inside one message
              (attachment)           an image or file was posted; you cannot see it

            WHAT TO WRITE, IN THIS ORDER
            1. The Story So Far: the server's arc from the first login to the end, split into named eras
               (for example the early scramble, big builds, wars, boss fights, the quiet final days).
            2. Funniest Moments: the top 10. For each give the date, who was involved and a short quote
               of the key line, then one or two sentences on why it is funny.
            3. Best Sequences: multi-message stretches that play out like a scene (disasters, heists,
               rescues, arguments, chains of deaths, schemes that backfired). Describe the setup, the
               escalation and the payoff.
            4. Hall of Shame: the most absurd, ironic or avoidable deaths. Use the death lines and the
               chat around them.
            5. Running Jokes and Catchphrases: things people kept saying or doing.
            6. Player Awards: one tongue-in-cheek award for every player, backed by BOTH the statistics
               and something from the log.
            7. By the Numbers: the most surprising statistics, with commentary. Compare players where it
               is funny (rivalries, close races, one player dominating a category).
            8. Closing Toast: a short, warm send-off for the server.

            RULES
            - Only use events that appear in the log or the statistics. Never invent quotes, dates or
              events. If you are not sure something happened, leave it out.
            - Quote short fragments exactly as written, under 20 words each.
            - Keep it affectionate: tease, don't wound. Skip anything that looks like a real personal
              conflict, a private matter or sensitive information.
            - Deaths and advancements are the most reliable timeline anchors; the statistics are final totals.
            - Format for Discord markdown (bold, # headers, bullet lists). Split your answer into chunks
              of under 1,900 characters, each starting with a line "--- Part N ---", so it can be pasted
              into Discord one message at a time.

            IF THIS PROMPT ARRIVES IN SEVERAL PARTS
            Files or messages may be marked "=== PART X OF Y ===". Until you have received the part
            marked (FINAL), reply only with the word: ready
            """;

    private OverviewPromptBuilder()
    {
    }

    /**
     * Facts about the log shown to the LLM and in the requester's summary.
     *
     * @param cap the configured message limit, reported when {@code capped} is true
     */
    public record Meta(String guildName, String channelName, String requester, int scanned, int kept,
                       boolean capped, int cap, OffsetDateTime first, OffsetDateTime last)
    {
    }

    /**
     * @return the complete prompt: instructions, server info, statistics and log
     */
    public static String build(final Meta meta, final String statsText, final String transcript)
    {
        final String stats = (statsText == null || statsText.isBlank())
                ? "(no statistics available)"
                : statsText.strip();
        final String log = (transcript == null || transcript.isBlank())
                ? "(no messages found)\n"
                : transcript;

        final StringBuilder out = new StringBuilder(INSTRUCTIONS.length() + stats.length() + log.length() + 1024);
        out.append(INSTRUCTIONS).append('\n');
        appendInfo(out, meta);
        out.append("\n=== FINAL SERVER STATISTICS ===\n").append(stats).append("\n\n");
        out.append("=== CHAT LOG (oldest first, times in UTC) ===\n").append(log);
        out.append("=== END OF LOG ===\n");
        out.append("Now write the recap following the instructions at the top.\n");
        return out.toString();
    }

    private static void appendInfo(final StringBuilder out, final Meta meta)
    {
        out.append("=== SERVER INFO ===\n");
        if (meta == null)
        {
            out.append("(unavailable)\n");
            return;
        }
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        out.append("Discord server: ").append(meta.guildName()).append('\n');
        out.append("Bridge channel: #").append(meta.channelName()).append('\n');
        out.append("Log covers: ").append(format(meta.first())).append(" to ")
                .append(format(meta.last())).append(" UTC\n");
        out.append("Messages scanned: ").append(fmt.format(meta.scanned()))
                .append(", log lines kept: ").append(fmt.format(meta.kept())).append('\n');
        out.append("Prepared for: ").append(meta.requester()).append('\n');
        if (meta.capped())
        {
            out.append("NOTE: only the newest ").append(fmt.format(meta.cap()))
                    .append(" messages were read, so the very beginning of the server is missing. ")
                    .append("Mention this briefly in the recap.\n");
        }
    }

    private static String format(final OffsetDateTime time)
    {
        return time == null ? "unknown" : TIME.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /* ------------------------------------------------------------------ */
    /* Splitting.                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Splits the prompt at line boundaries so no part exceeds {@code maxBytes}
     * of UTF-8. A prompt that already fits is returned unchanged as one part.
     *
     * @return at least one part, never null
     */
    public static List<String> split(final String prompt, final int maxBytes)
    {
        if (maxBytes <= MARKER_RESERVE_BYTES * 2)
        {
            throw new IllegalArgumentException("maxBytes is too small: " + maxBytes);
        }
        final List<String> parts = new ArrayList<>();
        if (prompt == null || prompt.isEmpty())
        {
            parts.add("");
            return parts;
        }
        if (utf8Length(prompt) <= maxBytes)
        {
            parts.add(prompt);
            return parts;
        }

        final List<String> chunks = chunk(prompt, maxBytes - MARKER_RESERVE_BYTES);
        final int total = chunks.size();
        for (int i = 0; i < total; i++)
        {
            parts.add(mark(chunks.get(i), i + 1, total));
        }
        return parts;
    }

    private static List<String> chunk(final String prompt, final int budget)
    {
        final List<String> chunks = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int currentBytes = 0;

        for (final String rawLine : prompt.split("\n", -1))
        {
            // Four bytes per char is the UTF-8 worst case, so this cut always fits.
            final String line = utf8Length(rawLine) + 1 > budget
                    ? rawLine.substring(0, budget / 4 - 1)
                    : rawLine;
            final int lineBytes = utf8Length(line) + 1;
            if (currentBytes + lineBytes > budget && current.length() > 0)
            {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(line).append('\n');
            currentBytes += lineBytes;
        }
        if (current.length() > 0)
        {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String mark(final String chunk, final int index, final int total)
    {
        final boolean last = index == total;
        final StringBuilder out = new StringBuilder(chunk.length() + 256);
        out.append("=== PART ").append(index).append(" OF ").append(total)
                .append(last ? " (FINAL) ===\n" : " ===\n");
        out.append(chunk);
        out.append("=== END OF PART ").append(index).append(" OF ").append(total);
        if (last)
        {
            out.append(" (FINAL). You now have everything. ===\n");
        }
        else
        {
            out.append(". More follows: reply only with the word ready. ===\n");
        }
        return out.toString();
    }

    /**
     * Counts UTF-8 bytes without allocating a byte array.
     */
    private static int utf8Length(final String text)
    {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c < 0x80)
            {
                bytes += 1;
            }
            else if (c < 0x800)
            {
                bytes += 2;
            }
            else if (Character.isHighSurrogate(c))
            {
                bytes += 4;
                i++;
            }
            else
            {
                bytes += 3;
            }
        }
        return bytes;
    }
}
