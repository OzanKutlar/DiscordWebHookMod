package com.example.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Turns a Discord message object into something safe to show in Minecraft chat.
 */
final class DiscordMessageFormatter
{
    private static final char SECTION_SIGN = '\u00A7';
    private static final String UNKNOWN_AUTHOR = "unknown";
    private static final String ATTACHMENT_MARKER = "(attachment)";

    private DiscordMessageFormatter()
    {
    }

    /**
     * Prefers the server nickname, then the global display name, then the username.
     */
    static String authorName(final JsonObject message)
    {
        final JsonObject member = childObject(message, "member");
        if (member != null)
        {
            final String nick = string(member, "nick");
            if (!nick.isEmpty())
            {
                return clean(nick);
            }
        }
        final JsonObject author = childObject(message, "author");
        if (author != null)
        {
            final String globalName = string(author, "global_name");
            if (!globalName.isEmpty())
            {
                return clean(globalName);
            }
            final String username = string(author, "username");
            if (!username.isEmpty())
            {
                return clean(username);
            }
        }
        return UNKNOWN_AUTHOR;
    }

    /**
     * @return the message body, cleaned and truncated. Empty means do not relay.
     */
    static String contentOf(final JsonObject message)
    {
        String content = clean(string(message, "content")).trim();

        if (DiscordConfig.relayAttachments && hasAttachments(message))
        {
            content = content.isEmpty() ? ATTACHMENT_MARKER : content + " " + ATTACHMENT_MARKER;
        }

        final int max = DiscordConfig.maxRelayedLength;
        if (content.length() > max)
        {
            content = content.substring(0, Math.max(1, max - 3)) + "...";
        }
        return content;
    }

    static Component toChatComponent(final String author, final String content)
    {
        return Component.literal(author).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(": " + content).withStyle(ChatFormatting.WHITE));
    }

    private static boolean hasAttachments(final JsonObject message)
    {
        final JsonElement attachments = message.get("attachments");
        if (attachments == null || !attachments.isJsonArray())
        {
            return false;
        }
        final JsonArray array = attachments.getAsJsonArray();
        return !array.isEmpty();
    }

    private static JsonObject childObject(final JsonObject parent, final String key)
    {
        if (parent == null)
        {
            return null;
        }
        final JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(final JsonObject parent, final String key)
    {
        if (parent == null)
        {
            return "";
        }
        final JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive())
        {
            return "";
        }
        final String value = element.getAsString();
        return value == null ? "" : value;
    }

    /**
     * Strips formatting codes and line breaks. Without this a Discord user could
     * colour or reformat server chat through their message or their nickname.
     */
    private static String clean(final String input)
    {
        if (input.isEmpty())
        {
            return input;
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
            if (c == '\n' || c == '\r' || c == '\t')
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
        return out.toString();
    }
}
