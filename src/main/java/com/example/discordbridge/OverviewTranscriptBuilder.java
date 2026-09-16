package com.example.discordbridge;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.User;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns bridge channel messages into compact one-line log entries for the
 * end-of-season overview prompt.
 *
 * <p>Entries are produced one message at a time, so the history fetch never has
 * to hold tens of thousands of full JDA message objects in memory.</p>
 */
public final class OverviewTranscriptBuilder
{
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final int MAX_LINE_LENGTH = 600;
    private static final char SECTION_SIGN = '§';
    private static final String ADMIN_PREFIX = "[ADMIN] ";
    private static final String SKULL = ":skull:";
    private static final String TEST_MESSAGE = "Discord Bridge test message.";
    private static final Pattern EXECUTED = Pattern.compile("^\\[Executed (.+) command]$");
    private static final Set<String> BOT_TEXT_COMMANDS =
            Set.of("!players", "!stats", "!status", "!restart", "!cmd", "!clearchat", "!overview");

    private OverviewTranscriptBuilder()
    {
    }

    /**
     * One log line and the time it was posted.
     */
    public record Entry(OffsetDateTime time, String line)
    {
    }

    /**
     * @param text  the rendered log, oldest first, one entry per line
     * @param lines how many lines were written after collapsing repeats
     * @param first the time of the oldest entry, or null if there were none
     * @param last  the time of the newest entry, or null if there were none
     */
    public record Transcript(String text, int lines, OffsetDateTime first, OffsetDateTime last)
    {
    }

    /* ------------------------------------------------------------------ */
    /* Per-message conversion.                                             */
    /* ------------------------------------------------------------------ */

    /**
     * @param message the message to convert
     * @param selfId  this bot's own user id; its command replies are skipped
     * @return the entry for this message, or null if it is noise
     */
    public static Entry toEntry(final Message message, final String selfId)
    {
        if (message == null || !isConversational(message.getType()))
        {
            return null;
        }
        final User author = message.getAuthor();
        // Slash command replies carry a webhook id but are authored by the bot itself.
        if (selfId != null && selfId.equals(author.getId()))
        {
            return null;
        }

        final String body;
        if (message.isWebhookMessage())
        {
            body = fromWebhook(message);
        }
        else if (author.isBot())
        {
            return null;
        }
        else
        {
            body = fromHuman(message);
        }

        if (body == null || body.isBlank())
        {
            return null;
        }
        return new Entry(message.getTimeCreated(), clamp(body));
    }

    private static boolean isConversational(final MessageType type)
    {
        return type == MessageType.DEFAULT || type == MessageType.INLINE_REPLY;
    }

    /**
     * Webhook posts are this mod's outbound relay: in-game chat, commands and
     * the event embeds.
     */
    private static String fromWebhook(final Message message)
    {
        if (!message.getEmbeds().isEmpty())
        {
            return fromEmbed(message.getEmbeds().get(0));
        }
        final String text = flatten(message.getContentDisplay());
        if (text.isEmpty() || TEST_MESSAGE.equals(text))
        {
            return null;
        }

        final String name = webhookName(message.getAuthor());
        final Matcher executed = EXECUTED.matcher(text);
        if (executed.matches())
        {
            return "» " + name + " ran " + executed.group(1);
        }
        if (name.isEmpty() || name.equals(DiscordConfig.username))
        {
            return "[SERVER] " + stripMarkdown(text);
        }
        return "<" + name + "> " + text;
    }

    private static String fromEmbed(final MessageEmbed embed)
    {
        final String rawDescription = embed.getDescription() == null ? "" : embed.getDescription();
        final String title = clean(embed.getTitle());
        final String description = clean(rawDescription.replace(SKULL, ""));

        if (rawDescription.contains(SKULL))
        {
            return "☠ " + description;
        }
        if (description.endsWith("joined the server") || description.endsWith("left the server"))
        {
            return "* " + description;
        }
        if ("Server Online".equals(title) || "Server Offline".equals(title))
        {
            return "[SERVER] " + title;
        }
        if (description.contains(" has made the advancement ")
                || description.contains(" has reached the goal ")
                || description.contains(" has completed the challenge "))
        {
            return "★ " + description;
        }
        if (!title.isEmpty() && !description.isEmpty())
        {
            return "• " + title + ": " + description;
        }
        final String only = title.isEmpty() ? description : title;
        return only.isEmpty() ? null : "• " + only;
    }

    private static String fromHuman(final Message message)
    {
        final String text = flatten(message.getContentDisplay());
        if (isBotCommand(text))
        {
            return null;
        }
        final int attachments = message.getAttachments().size();
        final boolean sticker = !message.getStickers().isEmpty();
        if (text.isEmpty() && attachments == 0 && !sticker)
        {
            return null;
        }

        final StringBuilder out = new StringBuilder(text.length() + 48);
        out.append("[DC] <").append(humanName(message.getMember(), message.getAuthor())).append('>');
        final Message referenced = message.getReferencedMessage();
        if (referenced != null)
        {
            out.append(" (reply to ").append(nameOf(referenced)).append(')');
        }
        if (!text.isEmpty())
        {
            out.append(' ').append(text);
        }
        if (attachments == 1)
        {
            out.append(" (attachment)");
        }
        else if (attachments > 1)
        {
            out.append(" (").append(attachments).append(" attachments)");
        }
        if (sticker)
        {
            out.append(" (sticker)");
        }
        return out.toString();
    }

    private static boolean isBotCommand(final String text)
    {
        if (text.isEmpty() || text.charAt(0) != '!')
        {
            return false;
        }
        final int space = text.indexOf(' ');
        final String root = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
        return BOT_TEXT_COMMANDS.contains(root);
    }

    private static String nameOf(final Message message)
    {
        return message.isWebhookMessage()
                ? webhookName(message.getAuthor())
                : humanName(message.getMember(), message.getAuthor());
    }

    private static String humanName(final Member member, final User user)
    {
        if (member != null)
        {
            return flatten(member.getEffectiveName());
        }
        return user == null ? "unknown" : flatten(user.getEffectiveName());
    }

    private static String webhookName(final User author)
    {
        final String name = author == null ? "" : flatten(author.getName());
        return name.startsWith(ADMIN_PREFIX) ? name.substring(ADMIN_PREFIX.length()) : name;
    }

    /* ------------------------------------------------------------------ */
    /* Rendering.                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Renders entries oldest first, collapsing consecutive identical lines into
     * one line with a repeat count.
     *
     * @param newestFirst entries in the order Discord returned them
     */
    public static Transcript render(final List<Entry> newestFirst)
    {
        if (newestFirst == null || newestFirst.isEmpty())
        {
            return new Transcript("", 0, null, null);
        }
        final List<Entry> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);

        final StringBuilder out = new StringBuilder(ordered.size() * 64);
        String pendingLine = null;
        String pendingStamp = null;
        int pendingCount = 0;
        int lines = 0;

        for (final Entry entry : ordered)
        {
            if (entry.line().equals(pendingLine))
            {
                pendingCount++;
                continue;
            }
            lines += flush(out, pendingStamp, pendingLine, pendingCount);
            pendingLine = entry.line();
            pendingStamp = stamp(entry.time());
            pendingCount = 1;
        }
        lines += flush(out, pendingStamp, pendingLine, pendingCount);

        return new Transcript(out.toString(), lines,
                ordered.get(0).time(), ordered.get(ordered.size() - 1).time());
    }

    private static int flush(final StringBuilder out, final String stamp, final String line, final int count)
    {
        if (line == null)
        {
            return 0;
        }
        out.append('[').append(stamp).append("] ").append(line);
        if (count > 1)
        {
            out.append(" (×").append(count).append(')');
        }
        out.append('\n');
        return 1;
    }

    private static String stamp(final OffsetDateTime time)
    {
        if (time == null)
        {
            return "unknown time";
        }
        return STAMP.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /* ------------------------------------------------------------------ */
    /* Text cleanup.                                                       */
    /* ------------------------------------------------------------------ */

    private static String clean(final String value)
    {
        return flatten(stripMarkdown(value == null ? "" : value));
    }

    private static String stripMarkdown(final String input)
    {
        return input.replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("||", "")
                .replace("`", "")
                .replace("*", "");
    }

    /**
     * Joins lines with " / ", drops formatting codes and control characters.
     */
    private static String flatten(final String input)
    {
        if (input == null || input.isEmpty())
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++)
        {
            final char c = input.charAt(i);
            if (c == SECTION_SIGN)
            {
                i++; // also drop the formatting character that follows
                continue;
            }
            if (c == '\n')
            {
                out.append(" / ");
                continue;
            }
            if (c == '\t')
            {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c))
            {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private static String clamp(final String line)
    {
        if (line.length() <= MAX_LINE_LENGTH)
        {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH - 1) + "…";
    }
}
