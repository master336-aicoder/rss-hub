package com.rsshub.feedfetch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The queue is the schedule (03 §4.2), and a queue that is empty at boot means every subscription silently stops
 * updating until someone presses refresh. This arms what is missing.
 *
 * <p>{@link TaskQueue#enqueue} is a no-op when a row already exists, so running this on every boot — including one
 * where the H2 file or the Postgres cluster survived — is safe, and there is no "first run" flag to get wrong.
 *
 * <h2>Staggering</h2>
 * Fresh fire times for every feed at once would open {@code concurrency} sockets to a hundred unrelated hosts the
 * second the app starts, and the first run after an import is exactly when a publisher is least obliged to be
 * patient. Rows are therefore armed over one interval rather than all at {@code now}; a manual add still means now.
 */
@Component
public class FetchQueueSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FetchQueueSeeder.class);

    /** How far apart two armed feeds sit, in the worst case of a full spread. */
    private static final Duration STEP = Duration.ofSeconds(20);

    /** Never wait longer than this for the last feed in a large import. */
    private static final Duration MAX_SPREAD = Duration.ofMinutes(10);

    private final FeedFetchRepository feeds;
    private final TaskQueue queue;

    public FetchQueueSeeder(FeedFetchRepository feeds, TaskQueue queue) {
        this.feeds = feeds;
        this.queue = queue;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> ids = feeds.schedulableFeedIds();
        if (ids.isEmpty()) {
            return;
        }
        Duration spread = Duration.ofSeconds(Math.min(ids.size() * STEP.toSeconds(), MAX_SPREAD.toSeconds()));
        Instant now = Instant.now();
        int armed = 0;
        for (int index = 0; index < ids.size(); index++) {
            long feedId = ids.get(index);
            if (queue.hasRow("fetch", dedupeKey(feedId))) {
                continue;
            }
            // A single feed is the common case and has nothing to spread; with two or more, the last one sits at the
            // end of the window and the rest fall evenly between now and then.
            Instant fireAt = ids.size() < 2 ? now : now.plus(spread.multipliedBy(index).dividedBy(ids.size() - 1));
            queue.enqueue("fetch", dedupeKey(feedId), feedId, fireAt);
            armed++;
        }
        if (armed > 0) {
            log.info("armed {} fetch task(s) for subscriptions the queue had no row for, spread over {}",
                armed, spread);
        }
    }

    /** The key {@code adminapi} and the tests both use: one row per feed, per kind. */
    public static String dedupeKey(long feedId) {
        return "feed:" + feedId;
    }
}
