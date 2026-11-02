package com.rsshub.rules;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code notification_log}: one row per (hit, channel) — 03 §8.1's "命中事实" and 03 §8.3's delivery outcome in the
 * same table, separated by {@code state}.
 *
 * <h2>Why the rules side owns this table</h2>
 * The row is written where the decision is made, inside the ingest transaction. Delivery lives in {@code notify},
 * which reads the row, POSTs, and moves it to a terminal state; that is the only direction the dependency goes, and
 * it keeps {@code rules} free of the network.
 *
 * <h2>The states</h2>
 * {@code pending} is queued work, {@code sent}/{@code failed} are its outcomes, and {@code suppressed}/
 * {@code skipped_quiet} never leave the table: they are the record that a rule hit and chose not to speak, which is
 * what the inbox mark and FR-5.3's audit need.
 */
@Repository
public class NotificationRepository {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String SUPPRESSED = "suppressed";
    public static final String FAILED = "failed";
    public static final String SKIPPED_QUIET = "skipped_quiet";

    private static final String COLUMNS = "id, rule_id, entry_id, channel, dedupe_key, state, http_status, error, "
        + "attempts, created_at";

    private static final RowMapper<Row> ROW_MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    private final JdbcClient db;

    public NotificationRepository(JdbcClient db) {
        this.db = db;
    }

    /** One row as stored. {@code ruleId} is null once the rule was deleted — the log outlives it by design. */
    public record Row(long id, Long ruleId, Long entryId, String channel, String dedupeKey, String state,
        Integer httpStatus, String error, int attempts, Instant createdAt) {
    }

    public long record(String state, long ruleId, long entryId, String channel, String dedupeKey) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into notification_log (rule_id, entry_id, channel, dedupe_key, state, attempts, created_at) "
                + "values (?, ?, ?, ?, ?, 0, ?)")
            .params(ruleId, entryId, channel, cut(dedupeKey, 512), state, JdbcTimes.toSql(Instant.now()))
            .update(key, "id");
        Number generated = key.getKey();
        if (generated == null) {
            throw ApiException.internal("notification_not_stored",
                "Inserted a " + state + " notification for rule " + ruleId + " but got no identity back");
        }
        return generated.longValue();
    }

    /**
     * 03 §8.2's pre-send check. Measured from {@code created_at} because the table has no delivered-at column and a
     * notification is sent seconds after it is recorded — the two differ by the retry backoff at most, which the
     * operator chose to be minutes, not days.
     */
    public boolean hasRecentSent(String dedupeKey, Instant since) {
        return db.sql("select 1 from notification_log where dedupe_key = ? and state = 'sent' and created_at >= ?"
                + " limit 1")
            .params(cut(dedupeKey, 512), JdbcTimes.toSql(since))
            .query(Integer.class)
            .optional()
            .isPresent();
    }

    public Optional<Row> find(long id) {
        return db.sql("select " + COLUMNS + " from notification_log where id = ?").param(id).query(ROW_MAPPER)
            .optional();
    }

    public List<Row> list(String state, Long ruleId, int limit) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            conditions.add("state = ?");
            params.add(state);
        }
        if (ruleId != null) {
            conditions.add("rule_id = ?");
            params.add(ruleId);
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        params.add(limit);
        return db.sql("select " + COLUMNS + " from notification_log" + where
                + " order by id desc fetch first ? rows only")
            .params(params)
            .query(ROW_MAPPER)
            .list();
    }

    public void markSent(long id, Integer httpStatus) {
        db.sql("update notification_log set state = 'sent', http_status = ?, error = null where id = ?")
            .params(httpStatus, id)
            .update();
    }

    /** A failed round: the attempt is counted and the reason kept, but the row stays reachable for a replay. */
    public void markFailed(long id, Integer httpStatus, String error) {
        db.sql("update notification_log set state = 'failed', http_status = ?, error = ?, attempts = attempts + 1"
                + " where id = ?")
            .params(httpStatus, cut(error, 1024), id)
            .update();
    }

    /** An attempt that did not end the row — the backoff will try again. */
    public void noteAttempt(long id, Integer httpStatus, String error) {
        db.sql("update notification_log set http_status = ?, error = ?, attempts = attempts + 1 where id = ?")
            .params(httpStatus, cut(error, 1024), id)
            .update();
    }

    public void resetToPending(long id) {
        int updated = db.sql("update notification_log set state = 'pending', attempts = 0, error = null where id = ?")
            .param(id)
            .update();
        if (updated == 0) {
            throw ApiException.notFound("unknown_notification", "No notification " + id);
        }
    }

    /**
     * How many delivery attempts this rule has failed in a row, newest first (03 §8.2's "连续 5 次投递失败").
     *
     * <p>It walks back over the rows that were attempted and sums their attempts, stopping at the first success:
     * one exhausted ladder is five attempts, so a rule is silenced after one entry it could not deliver rather than
     * after five of them. A suppressed or quiet hit is not an attempt and neither breaks nor feeds the run, and a
     * row left mid-ladder (state {@code pending}) has not failed yet, so it is not counted either.
     *
     * @param cap rows to look back over; the run stops early at a success, and the sum is capped by the caller
     */
    public int failedAttemptRun(long ruleId, int cap) {
        List<AttemptRow> rows = db.sql("select state, attempts from notification_log where rule_id = ? "
                + "and state in ('sent', 'failed') order by id desc limit " + cap)
            .param(ruleId)
            .query(AttemptRow.MAPPER)
            .list();
        int failures = 0;
        for (AttemptRow row : rows) {
            if (!FAILED.equals(row.state())) {
                break;
            }
            failures += row.attempts();
        }
        return failures;
    }

    private record AttemptRow(String state, int attempts) {

        private static final RowMapper<AttemptRow> MAPPER = (ResultSet rs, int rowNum) -> new AttemptRow(
            rs.getString("state"), rs.getInt("attempts"));
    }

    /** Every rule's name by id, for a delivery log that has to label its rows without one query per row. */
    public Map<Long, String> ruleNamesById() {
        Map<Long, String> names = new LinkedHashMap<>();
        db.sql("select id, name from rule")
            .query((ResultSet rs, int rowNum) -> Map.entry(rs.getLong("id"), rs.getString("name")))
            .list()
            .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    /** The status page's notification breakdown: how much spoke, was silenced, or is still waiting to go out. */
    public Map<String, Long> countsByState() {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.sql("select state, count(*) as n from notification_log group by state")
            .query((ResultSet rs, int rowNum) -> Map.entry(rs.getString("state"), rs.getLong("n")))
            .list()
            .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    /**
     * The same breakdown inside a window. The wall asks for it because "five failed" since the install began is a
     * fact about history; "five failed today" is a fact about whether to do something now.
     */
    public Map<String, Long> countsSince(Instant since) {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.sql("select state, count(*) as n from notification_log where created_at >= ? group by state")
            .param(JdbcTimes.toSql(since))
            .query((ResultSet rs, int rowNum) -> Map.entry(rs.getString("state"), rs.getLong("n")))
            .list()
            .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    /** Hits per hour, oldest first — the alert rail on the same axis as the arrivals histogram. */
    public Map<Instant, Long> hitsByHour(Instant since) {
        Map<Instant, Long> buckets = new LinkedHashMap<>();
        // hour_start, not hour: HOUR is reserved in H2 2.x, so the alias would be a syntax error.
        db.sql("""
            select date_trunc('hour', created_at) as hour_start, count(*) as hits
            from notification_log where created_at >= ?
            group by date_trunc('hour', created_at)
            order by date_trunc('hour', created_at)
            """)
            .param(JdbcTimes.toSql(since))
            .query((ResultSet rs, int rowNum) ->
                Map.entry(JdbcTimes.fromSql(rs, "hour_start"), rs.getLong("hits")))
            .list()
            .forEach(entry -> buckets.put(entry.getKey(), entry.getValue()));
        return buckets;
    }

    /**
     * One row of the wall's alert rail: a hit, the rule that spoke about it, and the entry it was about.
     *
     * @param entryTitle null when the entry has since been dropped with its feed — the log outlives the row, and
     *        the rail says so rather than hiding the hit
     */
    public record Alert(long id, String ruleName, String state, String channel, Instant createdAt, Long entryId,
        String entryTitle) {
    }

    /** The most recent hits, newest first, with the rule name resolved the same way the inbox badge resolves it. */
    public List<Alert> recent(int limit) {
        return db.sql("""
            select l.id, coalesce(r.name, ?) as rule_name, l.state, l.channel, l.created_at, l.entry_id,
                   e.title as entry_title
            from notification_log l
            left join rule r on r.id = l.rule_id
            left join entry e on e.id = l.entry_id
            order by l.id desc fetch first ? rows only
            """)
            .params(GONE_RULE_NAME, limit)
            .query((ResultSet rs, int rowNum) -> new Alert(rs.getLong("id"), rs.getString("rule_name"),
                rs.getString("state"), rs.getString("channel"), JdbcTimes.fromSql(rs, "created_at"),
                rs.getObject("entry_id", Long.class), rs.getString("entry_title")))
            .list();
    }

    /** FR-5.3's inbox mark: which rules have already spoken about these entries. */
    public Map<Long, List<String>> ruleNamesByEntries(List<Long> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();
        params.add(GONE_RULE_NAME);
        for (long id : entryIds) {
            placeholders.append(params.size() == 1 ? "?" : ", ?");
            params.add(id);
        }
        // The name comes from the live rule row; a rule the operator deleted is still a reason the entry is marked,
        // because the log outlived it, so coalesce() supplies the placeholder the first parameter carries.
        Map<Long, List<String>> hits = new LinkedHashMap<>();
        db.sql("select distinct l.entry_id as entry_id, coalesce(r.name, ?) as rule_name "
                + "from notification_log l left join rule r on r.id = l.rule_id "
                + "where l.entry_id in (" + placeholders + ") order by l.entry_id, rule_name")
            .params(params)
            .query((ResultSet rs, int rowNum) -> new Hit(rs.getLong("entry_id"), rs.getString("rule_name")))
            .list()
            .forEach(hit -> hits.computeIfAbsent(hit.entryId(), key -> new ArrayList<>()).add(hit.ruleName()));
        return hits;
    }

    private record Hit(long entryId, String ruleName) {
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), nullableLong(rs, "rule_id"), nullableLong(rs, "entry_id"),
            rs.getString("channel"), rs.getString("dedupe_key"), rs.getString("state"),
            nullableInt(rs, "http_status"), rs.getString("error"), rs.getInt("attempts"),
            JdbcTimes.fromSql(rs, "created_at"));
    }

    public static final String GONE_RULE_NAME = "（规则已删除）";

    /** {@code wasNull()} only describes the column read immediately before it, hence one helper per column. */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
