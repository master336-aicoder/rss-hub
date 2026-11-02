package com.rsshub.feedfetch;

import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The queue from 03 §4.2: exactly one {@code fetch} row per feed, keyed by {@code dedupe_key}, re-armed instead
 * of re-inserted so the unique constraint makes double-scheduling impossible. The one-shot kinds a notification
 * uses (03 §8.1's {@code deliver}) share the table, the claim and the backoff, and differ only in that they end:
 * see {@link #finish}.
 *
 * <h2>Why the claim is not {@code FOR UPDATE SKIP LOCKED}</h2>
 * That is the Postgres wording in the design, and the dev database here is H2, which does not implement it
 * (03 §14 O9). Claiming instead does a conditional single-row update and trusts the affected-row count: only one
 * worker can flip a row from {@code pending} to {@code running}, in either dialect, because the write lock on
 * that row serialises the two statements. The cost is one extra statement per task per round; at self-hosted
 * volumes that is invisible, and it buys a queue that behaves identically under both databases.
 */
@Repository
public class TaskQueue {

    /** Candidate rows per claim attempt. Small on purpose: contention between two workers is rare and cheap. */
    private static final int CLAIM_CANDIDATES = 5;

    private static final String TASK_COLUMNS = "id, kind, ref_id, dedupe_key, attempts, max_attempts, payload";

    private static final RowMapper<Task> TASK_ROW = (ResultSet rs, int rowNum) -> new Task(
        rs.getLong("id"),
        rs.getString("kind"),
        rs.getLong("ref_id"),
        rs.getString("dedupe_key"),
        rs.getInt("attempts"),
        rs.getInt("max_attempts"),
        readPayload(rs));

    private final JdbcClient db;

    public TaskQueue(JdbcClient db) {
        this.db = db;
    }

    /**
     * A queued unit of work. {@code payload} carries the per-task knobs (today: nothing, later the AI policy).
     */
    public record Task(long id, String kind, long refId, String dedupeKey, int attempts, int maxAttempts,
        String payload) {
    }

    /**
     * Registers work for {@code refId} if nothing is queued for it yet. Insert-then-check rather than an upsert
     * because {@code ON CONFLICT} is Postgres-only and {@code MERGE} is not portable either; the unique key is
     * what actually guarantees at-most-one row, and racing here only ever means a duplicate add attempt fails.
     */
    public void enqueue(String kind, String dedupeKey, long refId, Instant fireAt) {
        enqueue(kind, dedupeKey, refId, fireAt, "{}");
    }

    /**
     * @param payload the work's own data, not a knob: a {@code deliver} row carries the rendered notification
     *     because {@code notification_log} keeps no copy of the entry text or the channel target (03 §8.2), and a
     *     delivery that has to re-read a row the rule was deleted from would have nothing left to send.
     */
    public void enqueue(String kind, String dedupeKey, long refId, Instant fireAt, String payload) {
        Object counted = db.sql("select count(*) from scheduled_task where kind = ? and dedupe_key = ?")
            .params(kind, dedupeKey)
            .query()
            .singleValue();
        if (((Number) counted).longValue() > 0) {
            return;
        }
        db.sql("insert into scheduled_task (kind, ref_id, dedupe_key, state, fire_at, payload) "
                + "values (?, ?, ?, 'pending', ?, ?)")
            .params(kind, refId, dedupeKey, JdbcTimes.toSql(fireAt), payload)
            .update();
    }

    /**
     * Takes the earliest due row for {@code kind} and marks it running. Returns empty when nothing is due, which
     * is the normal case between rounds.
     */
    public Optional<Task> claim(String kind, Instant now, String worker) {
        // The candidate count is a literal: "limit ?" is not portable enough to bet the queue on.
        List<Long> candidates = db.sql("select id from scheduled_task "
                + "where kind = ? and state = 'pending' and fire_at <= ? order by fire_at, id limit "
                + CLAIM_CANDIDATES)
            .params(kind, JdbcTimes.toSql(now))
            .query(Long.class)
            .list();

        for (Long id : candidates) {
            // The update repeats the candidate predicate rather than trusting the SELECT: between the two
            // statements another worker may have finished with this row and re-armed it into the future, and a
            // claim that ignores fire_at would fetch a feed early. Checked in one statement, so the row lock
            // serialises it against the other worker's write.
            int claimed = db.sql("update scheduled_task set state = 'running', claimed_at = ?, locked_by = ?, "
                    + "attempts = attempts + 1, updated_at = ? where id = ? and state = 'pending' and fire_at <= ?")
                .params(JdbcTimes.toSql(now), worker, JdbcTimes.toSql(now), id, JdbcTimes.toSql(now))
                .update();
            if (claimed != 1) {
                continue;
            }
            return db.sql("select " + TASK_COLUMNS + " from scheduled_task where id = ?")
                .param(id)
                .query(TASK_ROW)
                .optional();
        }
        return Optional.empty();
    }

    /**
     * Re-arms the row for the next round and clears the error, so the queue itself is the schedule: there is no
     * separate cron to keep in sync.
     */
    public void armNext(long taskId, Instant fireAt) {
        Instant now = Instant.now();
        db.sql("update scheduled_task set state = 'pending', fire_at = ?, attempts = 0, last_error = null, "
                + "locked_by = null, claimed_at = null, updated_at = ? where id = ?")
            .params(JdbcTimes.toSql(fireAt), JdbcTimes.toSql(now), taskId)
            .update();
    }

    /**
     * Keeps the task pending but pushes it out — the backoff lives in {@code fireAt}, and {@code attempts} is
     * what the caller used to compute it.
     */
    public void armBackoff(long taskId, Instant fireAt, String error) {
        db.sql("update scheduled_task set state = 'pending', fire_at = ?, last_error = ?, locked_by = null, "
                + "claimed_at = null, updated_at = ? where id = ?")
            .params(JdbcTimes.toSql(fireAt), truncate(error), JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }

    /**
     * A 410 means the source told us to stop asking. Leaving the row {@code cancelled} (rather than deleting it)
     * keeps the reason on screen if the operator revives the feed later.
     */
    public void cancel(long taskId, String reason) {
        db.sql("update scheduled_task set state = 'cancelled', last_error = ?, locked_by = null, updated_at = ? "
                + "where id = ?")
            .params(truncate(reason), JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }

    /** Moves a cancelled row back into the queue, for "I changed my mind about this feed". */
    public void revive(long taskId, Instant fireAt) {
        db.sql("update scheduled_task set state = 'pending', fire_at = ?, attempts = 0, last_error = null, "
                + "updated_at = ? where id = ? and state = 'cancelled'")
            .params(JdbcTimes.toSql(fireAt), JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }

    /**
     * Parks the row for a feed the operator just paused, by the same key the scheduler uses. Missing row is not an
     * error: pausing a feed that was never queued leaves nothing to park.
     */
    public void cancelByKey(String kind, String dedupeKey, String reason) {
        findByDedupeKey(kind, dedupeKey).ifPresent(task -> cancel(task.id(), reason));
    }

    /**
     * Pulls the due time forward without creating a second row — the manual refresh button must not be able to
     * stack up work for one feed. Only a {@code pending} row moves: {@code running} means someone is already
     * fetching it, and {@code cancelled} is a separate decision the caller has to make explicitly.
     */
    public void scheduleNow(String kind, long refId, Instant fireAt) {
        db.sql("update scheduled_task set fire_at = ?, updated_at = ? where kind = ? and ref_id = ? "
                + "and state = 'pending'")
            .params(JdbcTimes.toSql(fireAt), JdbcTimes.toSql(Instant.now()), kind, refId)
            .update();
    }

    public Optional<Task> findByDedupeKey(String kind, String dedupeKey) {
        return db.sql("select " + TASK_COLUMNS + " from scheduled_task where kind = ? and dedupe_key = ?")
            .params(kind, dedupeKey)
            .query(TASK_ROW)
            .optional();
    }

    /** Whether the queue owns a row at all — the difference between "arm it" and "revive it" for the caller. */
    public boolean hasRow(String kind, String dedupeKey) {
        return findRow(kind, dedupeKey).isPresent();
    }

    /**
     * Puts one feed's work at the front of the queue, whatever state that row is in today: missing means insert,
     * {@code cancelled} means revive, and anything else means pull {@code fire_at} forward. This is the whole
     * contract behind a "refresh now" button, and the three cases are why it lives here rather than in the caller —
     * a controller that only knew about {@code pending} would leave a revived-after-410 feed looking alive but never
     * fetching.
     */
    public void armNow(String kind, String dedupeKey, long refId, Instant fireAt) {
        Optional<Row> row = findRow(kind, dedupeKey);
        if (row.isEmpty()) {
            enqueue(kind, dedupeKey, refId, fireAt);
            return;
        }
        if (row.get().state().equals("cancelled")) {
            revive(row.get().id(), fireAt);
        } else {
            scheduleNow(kind, refId, fireAt);
        }
    }

    /** id plus state, which is all the decisions above need and what {@code Task} deliberately does not carry. */
    private Optional<Row> findRow(String kind, String dedupeKey) {
        return db.sql("select id, state from scheduled_task where kind = ? and dedupe_key = ?")
            .params(kind, dedupeKey)
            .query((ResultSet rs, int rowNum) -> new Row(rs.getLong("id"), rs.getString("state")))
            .optional();
    }

    private record Row(long id, String state) {
    }

    /**
     * Ends a row: a notification either went out or it did not, and unlike a subscription there is no next round to
     * wait for. Keeping the row (rather than deleting it) is what lets the UI count attempts and replay by hand.
     */
    public void finish(long taskId, String state, String error) {
        db.sql("update scheduled_task set state = ?, last_error = ?, locked_by = null, claimed_at = null, "
                + "updated_at = ? where id = ?")
            .params(state, truncate(error), JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }

    /**
     * Puts finished work back at the front of the queue with fresh data. This is a notification replay: attempts
     * start again at zero, because the operator is asking for a new delivery, not continuing the old one.
     */
    public void armWithPayload(long taskId, Instant fireAt, String payload) {
        db.sql("update scheduled_task set state = 'pending', fire_at = ?, attempts = 0, last_error = null, "
                + "payload = ?, locked_by = null, claimed_at = null, updated_at = ? where id = ?")
            .params(JdbcTimes.toSql(fireAt), payload, JdbcTimes.toSql(Instant.now()), taskId)
            .update();
    }

    /**
     * Frees rows stuck in {@code running} because the process that claimed them died mid-work. Without this the
     * feed silently stops updating after a crash, which is the worst possible failure for a queue.
     *
     * <p>{@code kind} is part of the contract, not a filter: each dispatcher knows only its own work's time budget,
     * and a reaper that swept every kind with the fetcher's budget would kill a slow-but-alive notification the
     * moment the fetch round ran, or a mid-page fetch the moment the notifier's shorter budget fired.
     *
     * @return how many rows went back to {@code pending}; they are armed with a short delay by the caller
     */
    public int reapStaleRunning(String kind, Instant claimedBefore, Instant fireAt) {
        return db.sql("update scheduled_task set state = 'pending', fire_at = ?, locked_by = null, claimed_at = "
                + "null, last_error = ?, updated_at = ? where state = 'running' and kind = ? and claimed_at < ?")
            .params(JdbcTimes.toSql(fireAt), "reaped: worker disappeared", JdbcTimes.toSql(Instant.now()), kind,
                JdbcTimes.toSql(claimedBefore))
            .update();
    }

    public void deleteForFeed(long feedId) {
        db.sql("delete from scheduled_task where kind = 'fetch' and ref_id = ?")
            .param(feedId)
            .update();
    }

    /** The status page's queue breakdown: pending/running plus any terminal state rows still holding history. */
    public Map<String, Long> countsByState() {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.sql("select state, count(*) as n from scheduled_task group by state")
            .query((rs, rowNum) -> Map.entry(rs.getString("state"), rs.getLong("n")))
            .list()
            .forEach(pair -> counts.put(pair.getKey(), pair.getValue()));
        return counts;
    }

    private static String readPayload(ResultSet rs) throws SQLException {
        String payload = rs.getString("payload");
        return payload == null ? "{}" : payload;
    }

    /** {@code last_error} is VARCHAR(1024); an exception chain is easily longer than that. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
