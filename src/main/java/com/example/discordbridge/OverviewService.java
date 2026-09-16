package com.example.discordbridge;

import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and delivers the end-of-season overview prompt.
 *
 * <p>Flow: read the bridge channel history (JDA callback threads), flush and read
 * statistics (server thread), assemble the prompt (worker thread), then DM it to
 * the requester, falling back to a private interaction reply.</p>
 *
 * <p>Only one overview builds at a time. Every run ends in exactly one call to
 * {@link #release(boolean)}, through {@link #succeed} or {@link #fail}.</p>
 */
public final class OverviewService
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PAGE_SIZE = 100;
    private static final int PROGRESS_EVERY = 2_000;
    private static final int MAX_LOGGED_FAILURES = 3;
    private static final int MAX_DISCORD_MESSAGE = 2_000;
    private static final long FETCH_TIMEOUT_MINUTES = 30L;
    private static final long STATS_TIMEOUT_SECONDS = 60L;
    private static final long LARGE_TOKEN_WARNING = 150_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong LAST_SUCCESS_MS = new AtomicLong(0L);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(OverviewService::newWorkerThread);

    private OverviewService()
    {
    }

    /**
     * One uploadable file.
     */
    public record Part(String name, byte[] data)
    {
        FileUpload upload()
        {
            return FileUpload.fromData(data, name);
        }
    }

    /**
     * Where progress goes, and whether files can be delivered privately there.
     */
    public interface Reporter
    {
        void status(String text);

        /**
         * @return an action sending the files where only the requester can see
         *         them, or null if this surface has no private reply
         */
        RestAction<Message> privateFallback(String header, List<Part> parts);
    }

    private record Result(OverviewPromptBuilder.Meta meta, List<Part> parts, long characters)
    {
    }

    private static Thread newWorkerThread(final Runnable runnable)
    {
        final Thread thread = new Thread(runnable, "DiscordBridge-Overview");
        thread.setDaemon(true);
        return thread;
    }

    /* ------------------------------------------------------------------ */
    /* Access control.                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * @return the configured bridge channel, or null if it is unset or not visible
     */
    public static GuildMessageChannel bridgeChannel(final JDA jda)
    {
        if (jda == null || !DiscordConfig.isValidChannelId(DiscordConfig.channelId))
        {
            return null;
        }
        return jda.getChannelById(GuildMessageChannel.class, DiscordConfig.channelId);
    }

    /**
     * The owner, or anyone who can manage messages in the bridge channel.
     */
    public static boolean isAuthorized(final Member member, final GuildMessageChannel channel, final String userId)
    {
        if (DiscordConfig.isOwner(userId))
        {
            return true;
        }
        if (member == null || channel == null)
        {
            return false;
        }
        if (member.getGuild().getIdLong() != channel.getGuild().getIdLong())
        {
            return false;
        }
        return member.hasPermission(channel, Permission.MESSAGE_MANAGE);
    }

    /**
     * Claims the single run slot.
     *
     * @return null if the caller may proceed and now holds the slot, otherwise
     *         the reason they may not
     */
    public static String tryAcquire(final String userId)
    {
        if (!RUNNING.compareAndSet(false, true))
        {
            return "An overview is already being built. Try again once it finishes.";
        }
        if (DiscordConfig.isOwner(userId))
        {
            return null;
        }
        final long cooldownMs = DiscordConfig.overviewCooldownSeconds * 1000L;
        final long waitMs = LAST_SUCCESS_MS.get() + cooldownMs - System.currentTimeMillis();
        if (waitMs > 0L)
        {
            RUNNING.set(false);
            final long seconds = (waitMs + 999L) / 1000L;
            return "An overview was built recently. Try again in "
                    + (seconds / 60L) + "m " + (seconds % 60L) + "s.";
        }
        return null;
    }

    /**
     * Frees the run slot. A successful run starts the cooldown; a failed one does not.
     */
    public static void release(final boolean succeeded)
    {
        if (succeeded)
        {
            LAST_SUCCESS_MS.set(System.currentTimeMillis());
        }
        RUNNING.set(false);
    }

    /* ------------------------------------------------------------------ */
    /* Pipeline.                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Starts a run. The caller must already hold the slot from {@link #tryAcquire}.
     */
    public static void run(final MinecraftServer server, final GuildMessageChannel channel,
                           final User requester, final Reporter reporter)
    {
        if (reporter == null)
        {
            release(false);
            LOGGER.warn("Overview started without a reporter; aborting.");
            return;
        }
        if (server == null || channel == null || requester == null)
        {
            fail(reporter, "The server or the bridge channel is not available.", null);
            return;
        }
        try
        {
            start(server, channel, requester, reporter);
        }
        catch (final RuntimeException e)
        {
            fail(reporter, "Could not read the channel history: " + e.getMessage(), e);
        }
    }

    private static void start(final MinecraftServer server, final GuildMessageChannel channel,
                              final User requester, final Reporter reporter)
    {
        final Collector collector = new Collector(channel.getJDA().getSelfUser().getId(),
                DiscordConfig.maxOverviewMessages, reporter);
        reporter.status(":mag: Reading the history of #" + channel.getName()
                + ". This can take a few minutes on a busy channel...");

        final CompletableFuture<?> fetch = channel.getIterableHistory()
                .limit(PAGE_SIZE)
                .cache(false)
                .forEachAsync(collector::accept);

        fetch.thenRun(() -> reporter.status(":bar_chart: Read " + collector.scanned()
                        + " messages. Gathering the final stats..."))
                .orTimeout(FETCH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .thenCompose(ignored -> buildStatsOnServerThread(server))
                .thenApplyAsync(stats -> assemble(channel, requester, collector, stats), WORKER)
                .whenComplete((result, error) -> finishRun(requester, reporter, result, error));
    }

    private static void finishRun(final User requester, final Reporter reporter,
                                  final Result result, final Throwable error)
    {
        if (error != null)
        {
            fail(reporter, describe(error), error);
            return;
        }
        if (result == null || result.parts().isEmpty())
        {
            fail(reporter, "The overview came out empty.", null);
            return;
        }
        try
        {
            deliver(requester, reporter, result);
        }
        catch (final RuntimeException e)
        {
            fail(reporter, "Could not send the overview: " + e.getMessage(), e);
        }
    }

    private static CompletableFuture<String> buildStatsOnServerThread(final MinecraftServer server)
    {
        final CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try
            {
                PlayerStatsService.flushOnlineStats(server);
                future.complete(PlayerStatsService.buildStatsText(server));
            }
            catch (final RuntimeException e)
            {
                future.completeExceptionally(e);
            }
        });
        // If the server shuts down mid-run the task never executes; do not hang forever.
        return future.orTimeout(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Result assemble(final GuildMessageChannel channel, final User requester,
                                   final Collector collector, final String stats)
    {
        final OverviewTranscriptBuilder.Transcript transcript =
                OverviewTranscriptBuilder.render(collector.entries());
        final OverviewPromptBuilder.Meta meta = new OverviewPromptBuilder.Meta(
                channel.getGuild().getName(), channel.getName(), requester.getName(),
                collector.scanned(), transcript.lines(), collector.capped(), collector.max(),
                transcript.first(), transcript.last());

        final String prompt = OverviewPromptBuilder.build(meta, stats, transcript.text());
        final List<String> chunks = OverviewPromptBuilder.split(prompt, OverviewPromptBuilder.MAX_PART_BYTES);

        final String base = "smp-overview-" + DAY.format(OffsetDateTime.now(ZoneOffset.UTC));
        final List<Part> parts = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++)
        {
            final String name = chunks.size() == 1
                    ? base + ".txt"
                    : base + "-part" + (i + 1) + "of" + chunks.size() + ".txt";
            parts.add(new Part(name, chunks.get(i).getBytes(StandardCharsets.UTF_8)));
        }
        return new Result(meta, List.copyOf(parts), prompt.length());
    }

    /* ------------------------------------------------------------------ */
    /* Delivery.                                                           */
    /* ------------------------------------------------------------------ */

    private static void deliver(final User requester, final Reporter reporter, final Result result)
    {
        final String summary = summarize(result);
        final String header = deliveryHeader(result.parts().size());
        requester.openPrivateChannel()
                .flatMap(dm -> sendParts(dm, header, result.parts()))
                .queue(
                        sent -> succeed(reporter, summary + ":incoming_envelope: Sent to your DMs."),
                        error -> handleDmFailure(reporter, header, result, summary, error));
    }

    private static String deliveryHeader(final int partCount)
    {
        final String files = partCount == 1
                ? "the attached file"
                : "all " + partCount + " attached files, in order,";
        return ":scroll: **SMP overview prompt**\n"
                + "Upload " + files + " to your LLM (or open it and copy the text), "
                + "then post the recap it writes back to the group!";
    }

    /**
     * Sends one file per message, strictly in order.
     */
    private static RestAction<Message> sendParts(final MessageChannel channel, final String header,
                                                 final List<Part> parts)
    {
        if (parts.isEmpty())
        {
            throw new IllegalArgumentException("Nothing to send.");
        }
        RestAction<Message> chain = channel.sendMessage(header).addFiles(parts.get(0).upload());
        for (int i = 1; i < parts.size(); i++)
        {
            final Part part = parts.get(i);
            chain = chain.flatMap(previous -> channel.sendFiles(part.upload()));
        }
        return chain;
    }

    private static void handleDmFailure(final Reporter reporter, final String header, final Result result,
                                        final String summary, final Throwable error)
    {
        LOGGER.info("Could not DM the overview, trying the private fallback: {}", error.getMessage());
        final RestAction<Message> fallback = reporter.privateFallback(header, result.parts());
        if (fallback == null)
        {
            fail(reporter, "I couldn't DM you the overview. Allow direct messages from server members "
                    + "(right-click the server > Privacy Settings) and run the command again.", null);
            return;
        }
        fallback.queue(
                sent -> succeed(reporter, summary
                        + ":lock: I couldn't DM you, so the file is attached below where only you can see it."),
                fallbackError -> fail(reporter,
                        "Could not deliver the overview: " + fallbackError.getMessage(), fallbackError));
    }

    private static void succeed(final Reporter reporter, final String text)
    {
        release(true);
        reporter.status(text);
    }

    private static void fail(final Reporter reporter, final String text, final Throwable error)
    {
        release(false);
        if (error != null)
        {
            LOGGER.warn("Overview failed: {}", text, error);
        }
        else
        {
            LOGGER.warn("Overview failed: {}", text);
        }
        reporter.status(":x: " + text);
    }

    private static String describe(final Throwable error)
    {
        Throwable cause = error;
        for (int depth = 0; depth < 5 && cause.getCause() != null
                && (cause instanceof CompletionException || cause instanceof ExecutionException); depth++)
        {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException)
        {
            return "Timed out while building the overview. Try again, or lower maxOverviewMessages.";
        }
        final String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String summarize(final Result result)
    {
        final NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        final OverviewPromptBuilder.Meta meta = result.meta();
        final long tokens = result.characters() / 4L;

        final StringBuilder out = new StringBuilder(512);
        out.append(":white_check_mark: **Overview ready**\n");
        out.append("• Messages scanned: ").append(fmt.format(meta.scanned()))
                .append(" (").append(fmt.format(meta.kept())).append(" log lines kept)\n");
        if (meta.first() != null && meta.last() != null)
        {
            out.append("• Covers: ").append(DAY.format(meta.first().withOffsetSameInstant(ZoneOffset.UTC)))
                    .append(" → ").append(DAY.format(meta.last().withOffsetSameInstant(ZoneOffset.UTC)))
                    .append(" (UTC)\n");
        }
        out.append("• Size: ").append(fmt.format(result.characters()))
                .append(" characters, about ").append(fmt.format(tokens)).append(" tokens");
        if (result.parts().size() > 1)
        {
            out.append(", split into ").append(result.parts().size()).append(" files");
        }
        out.append('\n');
        if (tokens > LARGE_TOKEN_WARNING)
        {
            out.append(":warning: That is more than many LLMs accept at once. ")
                    .append("Use a long-context model, or upload the parts one by one.\n");
        }
        if (meta.capped())
        {
            out.append(":warning: Stopped at the `maxOverviewMessages` limit (")
                    .append(fmt.format(meta.cap())).append("), so the oldest messages are missing.\n");
        }
        return out.toString();
    }

    private static String limit(final String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= MAX_DISCORD_MESSAGE ? text : text.substring(0, MAX_DISCORD_MESSAGE - 3) + "...";
    }

    /* ------------------------------------------------------------------ */
    /* Reporters.                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * For the slash command: edits the ephemeral deferred reply, and can attach
     * files as ephemeral follow-ups.
     */
    public static Reporter hookReporter(final InteractionHook hook)
    {
        if (hook == null)
        {
            throw new IllegalArgumentException("hook must not be null");
        }
        hook.setEphemeral(true);
        return new Reporter()
        {
            @Override
            public void status(final String text)
            {
                hook.editOriginal(limit(text)).queue(null,
                        error -> LOGGER.debug("Could not update the /overview status: {}", error.getMessage()));
            }

            @Override
            public RestAction<Message> privateFallback(final String header, final List<Part> parts)
            {
                if (parts.isEmpty())
                {
                    return null;
                }
                RestAction<Message> chain = hook.sendMessage(header).addFiles(parts.get(0).upload());
                for (int i = 1; i < parts.size(); i++)
                {
                    final Part part = parts.get(i);
                    chain = chain.flatMap(previous -> hook.sendFiles(part.upload()));
                }
                return chain;
            }
        };
    }

    /**
     * For the {@code !overview} text command: edits a status message in the channel.
     */
    public static Reporter messageReporter(final Message statusMessage)
    {
        if (statusMessage == null)
        {
            throw new IllegalArgumentException("statusMessage must not be null");
        }
        return new Reporter()
        {
            @Override
            public void status(final String text)
            {
                statusMessage.editMessage(limit(text)).queue(null,
                        error -> LOGGER.debug("Could not update the !overview status: {}", error.getMessage()));
            }

            @Override
            public RestAction<Message> privateFallback(final String header, final List<Part> parts)
            {
                // A text command has no private reply surface, and posting everyone's
                // history publicly is not an acceptable fallback.
                return null;
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /* History collection.                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Converts messages to log entries as pages arrive, stopping at the cap.
     */
    private static final class Collector
    {
        private final String selfId;
        private final int max;
        private final Reporter reporter;
        private final List<OverviewTranscriptBuilder.Entry> newestFirst = new ArrayList<>();
        private int scanned;
        private int failures;

        Collector(final String selfId, final int max, final Reporter reporter)
        {
            this.selfId = selfId;
            this.max = Math.max(1, max);
            this.reporter = reporter;
        }

        /**
         * @return true to keep paging, false once the cap is reached
         */
        synchronized boolean accept(final Message message)
        {
            scanned++;
            try
            {
                final OverviewTranscriptBuilder.Entry entry = OverviewTranscriptBuilder.toEntry(message, selfId);
                if (entry != null)
                {
                    newestFirst.add(entry);
                }
            }
            catch (final RuntimeException e)
            {
                failures++;
                if (failures <= MAX_LOGGED_FAILURES)
                {
                    LOGGER.warn("Overview skipped a message it could not read: {}", e.getMessage());
                }
            }
            if (scanned % PROGRESS_EVERY == 0)
            {
                reporter.status(":mag: Read " + scanned + " messages so far...");
            }
            return scanned < max;
        }

        synchronized int scanned()
        {
            return scanned;
        }

        synchronized boolean capped()
        {
            return scanned >= max;
        }

        int max()
        {
            return max;
        }

        synchronized List<OverviewTranscriptBuilder.Entry> entries()
        {
            return new ArrayList<>(newestFirst);
        }
    }
}
