package com.example.discordbridge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a small box-drawn table as a chat {@link Component}.
 *
 * <p>Minecraft's default font is not monospaced, so columns are padded by
 * character count rather than pixel width. Typical alphanumeric usernames line
 * up well; a cell made entirely of narrow glyphs will sit slightly short.</p>
 *
 * <p>Usage is fluent and single-shot:</p>
 * <pre>
 *     new ChatTableBuilder()
 *         .header("Player", "Health")
 *         .row("Steve", "20.0 / 20.0")
 *         .build();
 * </pre>
 */
public final class ChatTableBuilder
{
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_ROWS = 64;
    private static final int MAX_CELL_WIDTH = 24;
    private static final String HORIZONTAL = "\u2500";
    private static final char VERTICAL = '\u2502';
    private static final char ELLIPSIS = '\u2026';

    private final List<String> headers = new ArrayList<>();
    private final List<String[]> rows = new ArrayList<>();
    private String emptyMessage = "(nothing to show)";
    private boolean truncated;

    /**
     * Defines the column headers. Clears any rows added so far.
     *
     * @throws IllegalArgumentException if no cells are given, or more than {@value #MAX_COLUMNS}
     */
    public ChatTableBuilder header(final String... cells)
    {
        if (cells == null || cells.length == 0)
        {
            throw new IllegalArgumentException("A table needs at least one column.");
        }
        if (cells.length > MAX_COLUMNS)
        {
            throw new IllegalArgumentException("A chat table supports at most " + MAX_COLUMNS + " columns.");
        }
        headers.clear();
        rows.clear();
        truncated = false;
        for (final String cell : cells)
        {
            headers.add(cell == null ? "" : cell);
        }
        return this;
    }

    /**
     * Sets the text shown in place of the table when there are no rows.
     */
    public ChatTableBuilder emptyMessage(final String message)
    {
        if (message != null && !message.isBlank())
        {
            emptyMessage = message;
        }
        return this;
    }

    /**
     * Appends one data row. Rows past {@value #MAX_ROWS} are discarded and a
     * truncation notice is appended to the built component.
     *
     * @throws IllegalStateException    if {@link #header} has not been called
     * @throws IllegalArgumentException if the cell count does not match the header
     */
    public ChatTableBuilder row(final String... cells)
    {
        if (headers.isEmpty())
        {
            throw new IllegalStateException("header(...) must be called before row(...).");
        }
        if (cells == null || cells.length != headers.size())
        {
            throw new IllegalArgumentException("Row must have exactly " + headers.size() + " cells.");
        }
        if (rows.size() >= MAX_ROWS)
        {
            truncated = true;
            return this;
        }
        final String[] copy = new String[cells.length];
        for (int i = 0; i < cells.length; i++)
        {
            copy[i] = cells[i] == null ? "" : cells[i];
        }
        rows.add(copy);
        return this;
    }

    /**
     * @return the rendered table, or the empty-state message if there are no rows
     */
    public Component build()
    {
        if (headers.isEmpty() || rows.isEmpty())
        {
            return Component.literal(emptyMessage).withStyle(ChatFormatting.GRAY);
        }

        final int[] widths = computeWidths();
        final MutableComponent out = Component.empty();

        out.append(border(widths, '\u250C', '\u252C', '\u2510'));
        out.append("\n");
        out.append(line(headers.toArray(new String[0]), widths, ChatFormatting.GOLD, ChatFormatting.BOLD));
        out.append("\n");
        out.append(border(widths, '\u251C', '\u253C', '\u2524'));

        for (final String[] row : rows)
        {
            out.append("\n");
            out.append(line(row, widths, ChatFormatting.WHITE));
        }

        out.append("\n");
        out.append(border(widths, '\u2514', '\u2534', '\u2518'));

        if (truncated)
        {
            out.append("\n");
            out.append(Component.literal("Output truncated at " + MAX_ROWS + " rows.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private int[] computeWidths()
    {
        final int columns = headers.size();
        final int[] widths = new int[columns];
        for (int i = 0; i < columns; i++)
        {
            widths[i] = Math.min(MAX_CELL_WIDTH, headers.get(i).length());
        }
        for (final String[] row : rows)
        {
            for (int i = 0; i < columns; i++)
            {
                widths[i] = Math.max(widths[i], Math.min(MAX_CELL_WIDTH, row[i].length()));
            }
        }
        for (int i = 0; i < columns; i++)
        {
            widths[i] = Math.max(1, widths[i]);
        }
        return widths;
    }

    private static MutableComponent border(final int[] widths, final char left, final char middle, final char right)
    {
        final StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++)
        {
            sb.append(HORIZONTAL.repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? right : middle);
        }
        return Component.literal(sb.toString()).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent line(final String[] cells, final int[] widths, final ChatFormatting... styles)
    {
        final MutableComponent out = Component.empty();
        for (int i = 0; i < widths.length; i++)
        {
            out.append(Component.literal(VERTICAL + " ").withStyle(ChatFormatting.DARK_GRAY));
            out.append(Component.literal(fit(cells[i], widths[i]) + " ").withStyle(styles));
        }
        out.append(Component.literal(String.valueOf(VERTICAL)).withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    private static String fit(final String cell, final int width)
    {
        final String text = cell.length() <= width
                ? cell
                : cell.substring(0, Math.max(0, width - 1)) + ELLIPSIS;
        return text + " ".repeat(Math.max(0, width - text.length()));
    }
}
