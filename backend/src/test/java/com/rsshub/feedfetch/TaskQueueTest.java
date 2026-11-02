package com.rsshub.feedfetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rsshub.feedfetch.TaskQueue.Task;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queue is the only place this project pretends to be multi-worker, so these tests are about the two promises
 * 03 §4.2 makes: a claim never hands the same row to two callers, and a crash never strands a row in
 * {@code running} forever. Both are checked with real concurrent connections, because a single-threaded test
 * cannot tell a conditional UPDATE apart from a plain one.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:taskqueue;DB_CLOSE_DELAY=-1",
    "rsshub.operator.username=admin",
    "rsshub.operator.password=a-test-only-password",
    "rsshub.fetch.enabled=false"
})
class TaskQueueTest {

    @Autowired
    TaskQueue queue;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearQueue() {
        jdbc.update("delete from scheduled_task");
    }

    @Test
    void enqueueIsIdempotentPerDedupeKey() {
        queue.enqueue("fetch", "feed:1", 1, Instant.now());
        queue.enqueue("fetch", "feed:1", 1, Instant.now().plusSeconds(600));

        long insertedId = queue.findByDedupeKey("fetch", "feed:1").orElseThrow().id();
        assertThat(jdbc.queryForObject("select count(*) from scheduled_task", Long.class)).isEqualTo(1);
        // Re-enqueueing must not move the schedule: the row keeps the fire time it was first armed with.
        assertThat(instant(insertedId, "fire_at")).isBefore(Instant.now().plusSeconds(60));
    }

    @Test
    void claimTakesTheEarliestDueRowAndMarksItRunning() {
        queue.enqueue("fetch", "feed:2", 2, Instant.now().plusSeconds(30));
        queue.enqueue("fetch", "feed:1", 1, Instant.now().minusSeconds(30));

        Task claimed = queue.claim("fetch", Instant.now(), "worker-a").orElseThrow();

        assertThat(claimed.dedupeKey()).isEqualTo("feed:1");
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.maxAttempts()).isEqualTo(5);
        assertThat(claimed.payload()).isEqualTo("{}");
        assertThat(row(claimed.id(), "state")).isEqualTo("running");
        assertThat(row(claimed.id(), "locked_by")).isEqualTo("worker-a");
        assertThat(instant(claimed.id(), "claimed_at")).isNotNull();
    }

    @Test
    void claimIgnoresFutureCancelledAndOtherKinds() {
        queue.enqueue("fetch", "feed:future", 1, Instant.now().plusSeconds(600));
        queue.enqueue("deliver", "feed:now", 2, Instant.now().minusSeconds(1));
        long cancelled = insert("fetch", 3, "feed:cancelled", Instant.now().minusSeconds(60));
        jdbc.update("update scheduled_task set state = 'cancelled' where id = ?", cancelled);

        assertThat(queue.claim("fetch", Instant.now(), "worker-a")).isEmpty();
        assertThat(queue.claim("deliver", Instant.now(), "worker-a")).isPresent();
    }

    @Test
    void twoWorkersNeverGetTheSameRow() throws Exception {
        int rows = 24;
        int workers = 6;
        for (int i = 0; i < rows; i++) {
            queue.enqueue("fetch", "feed:" + i, i, Instant.now().minusSeconds(rows - i));
        }

        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int w = 0; w < workers; w++) {
                String worker = "worker-" + w;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        ready.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Each worker re-arms what it took, so the candidate list another worker read a moment ago can
                    // point at a row that is pending again with a future fire time. Claiming that row is the bug
                    // this test found: the claim update has to re-check fire_at, not only state.
                    Instant deadline = Instant.now().plusSeconds(20);
                    while (Instant.now().isBefore(deadline)) {
                        Task task = queue.claim("fetch", Instant.now(), worker).orElse(null);
                        if (task == null) {
                            return;
                        }
                        if (!seen.add(task.id())) {
                            duplicates.incrementAndGet();
                        }
                        queue.armNext(task.id(), Instant.now().plusSeconds(3600));
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(duplicates.get()).isZero();
        // Each row was re-armed into the future, so the workers stop instead of spinning until the deadline.
        assertThat(seen).hasSize(rows);
        assertThat(jdbc.queryForObject("select count(*) from scheduled_task where state = 'pending'", Integer.class))
            .isEqualTo(rows);
    }

    @Test
    void armNextClearsTheHistoryOfTheRound() {
        long id = insert("fetch", 1, "feed:1", Instant.now().minusSeconds(5));
        jdbc.update("update scheduled_task set state='running', attempts=3, last_error='boom', locked_by='w', "
            + "claimed_at = CURRENT_TIMESTAMP where id = ?", id);

        queue.armNext(id, Instant.now().plusSeconds(60));

        assertThat(row(id, "state")).isEqualTo("pending");
        assertThat(row(id, "attempts")).isEqualTo("0");
        assertThat(row(id, "last_error")).isNull();
        assertThat(row(id, "locked_by")).isNull();
        assertThat(instant(id, "claimed_at")).isNull();
    }

    @Test
    void armBackoffKeepsAttemptsBecauseTheCallerDerivedTheDelayFromThem() {
        long id = insert("fetch", 1, "feed:1", Instant.now().minusSeconds(5));
        jdbc.update("update scheduled_task set state='running', attempts=4, locked_by='w' where id = ?", id);

        queue.armBackoff(id, Instant.now().plusSeconds(120), "http_500");

        assertThat(row(id, "state")).isEqualTo("pending");
        assertThat(row(id, "attempts")).isEqualTo("4");
        assertThat(row(id, "last_error")).isEqualTo("http_500");
    }

    @Test
    void cancelStaysCancelledUntilRevived() {
        long id = insert("fetch", 1, "feed:1", Instant.now().minusSeconds(5));
        queue.cancel(id, "HTTP 410");

        assertThat(row(id, "state")).isEqualTo("cancelled");
        assertThat(row(id, "last_error")).isEqualTo("HTTP 410");
        // A cancelled row is not work, whatever its fire time says.
        assertThat(queue.claim("fetch", Instant.now(), "w")).isEmpty();

        queue.revive(id, Instant.now());
        assertThat(row(id, "state")).isEqualTo("pending");
        assertThat(queue.claim("fetch", Instant.now(), "w")).isPresent();
    }

    @Test
    void reviveOnlyTouchesCancelledRows() {
        long id = insert("fetch", 1, "feed:1", Instant.now().plusSeconds(600));
        queue.armBackoff(id, Instant.now().plusSeconds(600), "rate_limited");

        queue.revive(id, Instant.now().minusSeconds(1));

        // A pending row pushed out by a backoff is not "cancelled", so reviving it must not undo the backoff.
        assertThat(instant(id, "fire_at")).isAfter(Instant.now());
    }

    @Test
    void scheduleNowDoesNotStackUpWorkOrInterruptARunningFetch() {
        long pending = insert("fetch", 1, "feed:1", Instant.now().plusSeconds(600));
        Instant scheduledFor = Instant.now().minusSeconds(60);
        long running = insert("fetch", 2, "feed:2", scheduledFor);
        jdbc.update("update scheduled_task set state='running', claimed_at=CURRENT_TIMESTAMP where id = ?", running);

        queue.scheduleNow("fetch", 1, Instant.now().minusSeconds(1));
        queue.scheduleNow("fetch", 2, Instant.now().minusSeconds(1));

        assertThat(instant(pending, "fire_at")).isBefore(Instant.now());
        // Someone is already fetching it: pulling its fire time forward would queue a second round.
        assertThat(instant(running, "fire_at")).isCloseTo(scheduledFor, within(Duration.ofSeconds(2)));
        assertThat(row(running, "state")).isEqualTo("running");
        assertThat(jdbc.queryForObject("select count(*) from scheduled_task", Long.class)).isEqualTo(2);
    }

    @Test
    void reapStaleRunningFreesOnlyTheRowsOlderThanTheBudget() {
        long stuck = insert("fetch", 1, "feed:1", Instant.now().minusSeconds(120));
        long healthy = insert("fetch", 2, "feed:2", Instant.now().minusSeconds(120));
        // Same age, different kind: the notifier's round must not free a fetch that is still in flight, and this
        // round must not free one of its own kind that the other dispatcher owns.
        long otherKind = insert("deliver", 3, "deliver:9", Instant.now().minusSeconds(120));
        jdbc.update("update scheduled_task set state='running', claimed_at = CURRENT_TIMESTAMP - INTERVAL '2' HOUR "
            + "where id = ?", stuck);
        jdbc.update("update scheduled_task set state='running', claimed_at = CURRENT_TIMESTAMP where id = ?", healthy);
        jdbc.update("update scheduled_task set state='running', claimed_at = CURRENT_TIMESTAMP - INTERVAL '2' HOUR "
            + "where id = ?", otherKind);

        int reaped = queue.reapStaleRunning("fetch", Instant.now().minusSeconds(300), Instant.now());

        assertThat(reaped).isEqualTo(1);
        assertThat(row(stuck, "state")).isEqualTo("pending");
        assertThat(row(stuck, "locked_by")).isNull();
        assertThat(row(stuck, "last_error")).isEqualTo("reaped: worker disappeared");
        assertThat(row(healthy, "state")).isEqualTo("running");
        assertThat(row(otherKind, "state")).isEqualTo("running");
    }

    @Test
    void deleteForFeedRemovesItsFetchTaskAndNothingElse() {
        insert("fetch", 1, "feed:1", Instant.now());
        insert("fetch", 2, "feed:2", Instant.now());
        // Same ref_id, different kind: a pending notification for feed 1 is not the feed's polling task.
        insert("deliver", 1, "hook:1", Instant.now());

        queue.deleteForFeed(1);

        List<String> keys = jdbc.queryForList("select dedupe_key from scheduled_task order by dedupe_key",
            String.class);
        assertThat(keys).containsExactly("feed:2", "hook:1");
    }

    @Test
    void longErrorsAreTruncatedNotRejected() {
        long id = insert("fetch", 1, "feed:1", Instant.now());
        String huge = "x".repeat(2000);

        queue.armBackoff(id, Instant.now().plusSeconds(60), huge);

        assertThat(row(id, "last_error")).hasSize(1024);
    }

    // --- fixtures ------------------------------------------------------------------------------------------

    private long insert(String kind, long refId, String dedupeKey, Instant fireAt) {
        jdbc.update("insert into scheduled_task (kind, ref_id, dedupe_key, state, fire_at, payload) "
                + "values (?, ?, ?, 'pending', ?, '{}')", kind, refId, dedupeKey,
            OffsetDateTime.ofInstant(fireAt, ZoneOffset.UTC));
        return jdbc.queryForObject("select id from scheduled_task where kind = ? and dedupe_key = ?",
            Long.class, kind, dedupeKey);
    }

    /** RowMapper-based readers: a SQL NULL is a meaningful answer for claimed_at and last_error, and these must not
     * trip the scalar readers' "value is null but no null value expected". */
    private String row(long id, String column) {
        return jdbc.query("select " + column + " from scheduled_task where id = ?",
            (rs, n) -> rs.getString(1), id).get(0);
    }

    private Instant instant(long id, String column) {
        OffsetDateTime value = jdbc.query("select " + column + " from scheduled_task where id = ?",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class), id).get(0);
        return value == null ? null : value.toInstant();
    }
}
