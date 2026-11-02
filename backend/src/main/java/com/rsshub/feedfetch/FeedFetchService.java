package com.rsshub.feedfetch;

import com.rsshub.common.SsrfBlockedException;
import com.rsshub.feedfetch.FetchFailedException.Kind;
import com.rsshub.feedfetch.FetchReport.Arm;
import com.rsshub.feedfetch.FetchReport.Feed;
import com.rsshub.feedfetch.FeedFetchRepository.Target;
import com.rsshub.feedfetch.TaskQueue.Task;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 03 §4.3, written as a decision sequence rather than as a set of handlers, because the order is the contract:
 * which outcome resets {@code fail_count}, which one halves the backoff, and which one stops asking forever.
 *
 * <p>No HTTP happens inside a transaction. The exchange runs first, then {@link FetchOutcomeWriter} applies the
 * decision, so a publisher that takes 30 seconds never holds a database connection.
 */
@Service
public class FeedFetchService {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchService.class);

    private final FeedFetchRepository feeds;
    private final HttpFeedFetcher http;
    private final HostPoliteness politeness;
    private final RawResponseStore rawStore;
    private final FetchOutcomeWriter writer;
    private final TaskQueue queue;
    private final FetchPolicy policy;
    private final List<FeedDocumentProcessor> processors;

    public FeedFetchService(FeedFetchRepository feeds, HttpFeedFetcher http, HostPoliteness politeness,
        RawResponseStore rawStore, FetchOutcomeWriter writer, TaskQueue queue, FetchPolicy policy,
        List<FeedDocumentProcessor> processors) {
        this.feeds = feeds;
        this.http = http;
        this.politeness = politeness;
        this.rawStore = rawStore;
        this.writer = writer;
        this.queue = queue;
        this.policy = policy;
        this.processors = processors;
    }

    /** Runs one claimed {@code fetch} task end to end. Exceptions here are always converted into a queue decision. */
    public void run(Task task) {
        Optional<Target> found = feeds.findForFetch(task.refId());
        if (found.isEmpty()) {
            queue.deleteForFeed(task.refId());
            return;
        }
        Target feed = found.get();
        if (feed.state().equals("gone") || feed.state().equals("paused")) {
            // Nothing to fetch and nobody to tell: the task parks itself, the feed keeps its last known state.
            queue.cancel(task.id(), "feed is " + feed.state());
            return;
        }

        Instant now = Instant.now();
        String host = URI.create(feed.canonicalUrl()).getHost();
        if (host == null) {
            writer.applyFailure(feed.id(), task.id(),
                Feed.of("error", null, feed.failCount(), feed.notfoundStreak(), "malformed_url"),
                Arm.backoffUntil(now.plus(policy.backoffFor(feed.failCount() + 1)), "malformed_url"));
            return;
        }

        Optional<Instant> deferred = politeness.reserve(host, now);
        if (deferred.isPresent()) {
            queue.armNext(task.id(), deferred.get());
            return;
        }
        try {
            exchange(feed, task, now);
        } catch (SsrfBlockedException e) {
            // Our own policy refused the target; the publisher did nothing wrong, so its counters stay put.
            log.warn("feed {} blocked by SSRF guard: {}", feed.id(), e.reason());
            writer.applyFailure(feed.id(), task.id(),
                Feed.of("error", null, feed.failCount(), feed.notfoundStreak(), "ssrf_blocked:" + e.reason()),
                Arm.until(now.plus(policy.authBackoff())));
        } catch (FetchFailedException e) {
            failed(feed, task, e, now);
        } catch (RuntimeException e) {
            log.atError().setCause(e).addKeyValue("feedId", feed.id()).log("unexpected fetch failure");
            punish(feed, task, now, "unexpected:" + e.getClass().getSimpleName());
        } finally {
            politeness.release(host);
        }
    }

    private void exchange(Target feed, Task task, Instant now) {
        HttpFeedFetcher.Response res =
            http.fetch(feed.canonicalUrl(), feed.etag(), feed.lastModifiedHdr());
        switch (res.status()) {
            case 304 -> notModified(feed, task, res, now);
            case 410 -> gone(feed, task, res);
            case 404 -> notFound(feed, task, res, now);
            case 429 -> rateLimited(feed, task, res, now);
            case 401, 403 -> authRequired(feed, task, res, now);
            default -> {
                if (res.isContent()) {
                    content(feed, task, res, now);
                } else {
                    serverSide(feed, task, res, now);
                }
            }
        }
    }

    /** 03 §4.3: a 304 is a healthy round. Counters reset, nothing is retained, the interval is unchanged. */
    private void notModified(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        writer.applyContent(feed.id(), task.id(), null, null,
            new Feed("active", 304, 0, 0, null, null, null, null, res.elapsedMs(), false),
            Arm.until(nextFire(now, feed)));
        log.debug("feed {} not modified", feed.id());
    }

    /** 03 §4.3 step 5: HTTP worked, the document did not parse. Keep the bytes and retry once in an hour. */
    private void content(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        // Retained before the html branch: the bytes are the evidence behind "this address is a web page, not a feed",
        // and content-addressing makes the daily repeat of an unchanged page nearly free.
        RawResponseStore.Saved saved = rawStore.save(feed.id(), res.status(), res.contentType(), res.body(), now);
        if (isHtml(res.contentType())) {
            log.warn("feed {} answered 200 with text/html; the subscription probably points at a web page",
                feed.id());
            writer.applyFailure(feed.id(), task.id(),
                new Feed("error", res.status(), feed.failCount(), feed.notfoundStreak(), "not_a_feed", res.etag(),
                    res.lastModified(), movedUrl(feed, res), res.elapsedMs(), false),
                Arm.until(nextFire(now, feed)));
            return;
        }

        int entryCount = 0;
        String format = null;
        boolean changed = false;
        try {
            for (FeedDocumentProcessor processor : processors) {
                FeedDocumentProcessor.Result result = processor.process(new FeedDocumentProcessor.Request(
                    feed.id(), res.finalUrl(), res.contentType(), res.body(), now));
                entryCount = Math.max(entryCount, result.entryCount());
                format = result.format() == null ? format : result.format();
                changed |= result.changed();
            }
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).addKeyValue("feedId", feed.id())
                .log("document could not be parsed, keeping raw bytes for one retry");
            writer.applyFailure(feed.id(), task.id(),
                new Feed("error", res.status(), feed.failCount() + 1, feed.notfoundStreak(), "parse_error",
                    res.etag(), res.lastModified(), movedUrl(feed, res), res.elapsedMs(), false),
                Arm.backoffUntil(now.plus(Duration.ofHours(1)), "parse_error"));
            return;
        }

        writer.applyContent(feed.id(), task.id(), saved.id(), format == null ? null : entryCount,
            new Feed("active", res.status(), 0, 0, null, res.etag(), res.lastModified(), movedUrl(feed, res),
                res.elapsedMs(), changed),
            Arm.until(nextFire(now, feed)));
        log.info("fetched feed {} status={} bytes={} entries={} changed={}", feed.id(), res.status(),
            saved.byteSize(), entryCount, changed);
    }

    /** A permanent move rewrites the subscription; a temporary one is only worth a log line (03 §4.3). */
    private String movedUrl(Target feed, HttpFeedFetcher.Response res) {
        if (!res.canonicalMoved() || feed.canonicalUrl().equals(res.finalUrl())) {
            return null;
        }
        log.info("feed {} moved permanently to {}", feed.id(), res.finalUrl());
        return res.finalUrl();
    }

    /** 03 §4.3: 410 is the publisher asking to be unsubscribed. Stop the task, keep the reason visible. */
    private void gone(Target feed, Task task, HttpFeedFetcher.Response res) {
        writer.applyFailure(feed.id(), task.id(),
            Feed.of("gone", 410, feed.failCount(), feed.notfoundStreak(), "gone_410"),
            Arm.cancelled("HTTP 410"));
    }

    /** 03 §4.3: one 404 is a hiccup, thirty days of them is a dead feed. */
    private void notFound(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        int streak = feed.notfoundStreak() + 1;
        Duration interval = policy.effectiveInterval(feed.fetchIntervalMinutes(), feed.ttlMinutes());
        if (interval.multipliedBy(streak).compareTo(policy.notfoundGoneDays()) >= 0) {
            writer.applyFailure(feed.id(), task.id(),
                Feed.of("gone", 404, feed.failCount(), streak, "not_found_for_" + streak + "_rounds"),
                Arm.cancelled("404 on every round for " + interval.multipliedBy(streak).toDays() + " days"));
            return;
        }
        int fails = feed.failCount() + 1;
        writer.applyFailure(feed.id(), task.id(),
            Feed.of("error", 404, fails, streak, "not_found"),
            Arm.backoffUntil(now.plus(policy.backoffFor(fails)), "not_found"));
    }

    /**
     * 03 §4.3: honour {@code Retry-After} rather than hammering, but cap it at the backoff ceiling — a publisher
     * that answers "come back in a year" would otherwise park a subscription with nothing on the health page
     * except a fire time nobody reads.
     */
    private void rateLimited(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        Instant fallback = now.plus(policy.backoffFor(Math.max(feed.failCount(), 1)));
        Instant when = minInstant(retryAfter(res.retryAfter(), now, fallback), now.plus(policy.backoffCap()));
        writer.applyFailure(feed.id(), task.id(),
            Feed.of("active", 429, feed.failCount(), feed.notfoundStreak(), "rate_limited"),
            Arm.backoffUntil(when, "rate_limited"));
    }

    /** 401/403 is not a transient failure; a day of silence beats a day of retries, and the operator gets a reason. */
    private void authRequired(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        writer.applyFailure(feed.id(), task.id(),
            Feed.of("error", res.status(), feed.failCount(), feed.notfoundStreak(), "auth_required"),
            Arm.until(now.plus(policy.authBackoff())));
    }

    /** 5xx and anything else the publisher answers: the exponential path from 03 §4.3 step 3. */
    private void serverSide(Target feed, Task task, HttpFeedFetcher.Response res, Instant now) {
        punish(feed, task, now, "http_" + res.status());
    }

    private void punish(Target feed, Task task, Instant now, String error) {
        int fails = feed.failCount() + 1;
        writer.applyFailure(feed.id(), task.id(),
            Feed.of("error", null, fails, feed.notfoundStreak(), error),
            Arm.backoffUntil(now.plus(policy.backoffFor(fails)), error));
    }

    private void failed(Target feed, Task task, FetchFailedException e, Instant now) {
        switch (e.kind()) {
            case SIZE_LIMIT -> {
                // Our limit, not the publisher's fault: half the backoff and no new failure counted (03 §4.3 step 2).
                Duration half = policy.backoffFor(Math.max(feed.failCount(), 1)).dividedBy(2);
                writer.applyFailure(feed.id(), task.id(),
                    Feed.of("active", null, feed.failCount(), feed.notfoundStreak(), "size_limit"),
                    Arm.backoffUntil(now.plus(half), "size_limit"));
            }
            case INTERRUPTED -> {
                queue.armNext(task.id(), now.plusSeconds(30));
            }
            default -> {
                log.info("feed {} could not be fetched ({}): {}", feed.id(), e.kind(), e.getMessage());
                punish(feed, task, now, e.kind().name().toLowerCase());
            }
        }
    }

    /** The operator's cadence for this feed beats its own ttl, and jitter keeps a hundred feeds from arriving at once. */
    private Instant nextFire(Instant now, Target feed) {
        Duration interval = policy.effectiveInterval(feed.fetchIntervalMinutes(), feed.ttlMinutes());
        long spread = policy.jitter().toSeconds();
        long offset = spread <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spread);
        return now.plus(interval).plusSeconds(offset);
    }

    /** RFC 9110 allows either a delta-seconds or an HTTP-date; anything else means "use our own backoff". */
    private static Instant retryAfter(String value, Instant now, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            return now.plusSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            // not a delta-seconds, try the date form
        }
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/html");
    }
}
