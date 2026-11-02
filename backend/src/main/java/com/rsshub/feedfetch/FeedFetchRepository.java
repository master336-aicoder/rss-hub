package com.rsshub.feedfetch;

import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code feed} columns the fetcher owns: URL, conditional-request validators, health counters and timing.
 * <p>
 * {@code feedstore.FeedRepository} reads the same table for the operator UI (title, grouping, listing). The split
 * is not duplication — it is what keeps {@code feedfetch} free of a dependency on {@code feedstore}, which the
 * pipeline needs in the other direction (feedstore implements {@link FeedDocumentProcessor}). Two repositories
 * over one table, each with its own column set, beats a package cycle.
 */
@Repository
public class FeedFetchRepository {

    private static final RowMapper<Target> TARGET_ROW = (ResultSet rs, int rowNum) -> new Target(
        rs.getLong("id"),
        rs.getString("canonical_url"),
        rs.getString("etag"),
        rs.getString("last_modified_hdr"),
        rs.getObject("ttl_minutes", Integer.class),
        rs.getObject("fetch_interval_minutes", Integer.class),
        rs.getInt("fail_count"),
        rs.getInt("notfound_streak"),
        rs.getString("state"),
        JdbcTimes.fromSql(rs, "last_change_at"));

    private final JdbcClient db;

    public FeedFetchRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param lastModifiedHdr the stored {@code Last-Modified} header, echoed back verbatim — reformatting it would
     *        break the comparison the publisher does
     * @param fetchIntervalMinutes the operator's own cadence for this feed, null when the publisher's {@code ttl} or
     *        the install default should decide; {@code ttlMinutes} is what the document reported
     */
    public record Target(long id, String canonicalUrl, String etag, String lastModifiedHdr, Integer ttlMinutes,
        Integer fetchIntervalMinutes, int failCount, int notfoundStreak, String state, Instant lastChangeAt) {
    }

    public Optional<Target> findForFetch(long feedId) {
        return db.sql("select id, canonical_url, etag, last_modified_hdr, ttl_minutes, fetch_interval_minutes, "
                + "fail_count, notfound_streak, state, last_change_at from feed where id = ?")
            .param(feedId)
            .query(TARGET_ROW)
            .optional();
    }

    /**
     * Writes the fetch bookkeeping for one completed exchange. {@code null} validators keep the stored value, so a
     * publisher that drops its ETag header between requests does not silently lose conditional fetching — the
     * coalesce happens in SQL because "absent" and "empty" are both real responses.
     */
    public void recordFetch(long feedId, String state, Integer httpStatus, int failCount, int notfoundStreak,
        String error, String etag, String lastModified) {
        Instant now = Instant.now();
        db.sql("update feed set state = ?, last_http_status = ?, fail_count = ?, notfound_streak = ?, "
                + "last_error = ?, etag = coalesce(?, etag), last_modified_hdr = coalesce(?, last_modified_hdr), "
                + "last_fetched_at = ?, updated_at = ? where id = ?")
            .params(state, httpStatus, failCount, notfoundStreak, truncate(error), etag, lastModified,
                JdbcTimes.toSql(now), JdbcTimes.toSql(now), feedId)
            .update();
    }

    /**
     * Records an exchange that produced content: the validators, the URL a permanent move pointed at, and a
     * smoothed fetch duration.
     *
     * @param canonicalUrl the URL after 301/308, or null when the feed did not move
     * @param elapsedMs duration of this exchange; averaged 3/4 old, 1/4 new so one slow poll does not distort the
     *        health view
     */
    public void recordContent(long feedId, String state, int httpStatus, String error, String etag,
        String lastModified, String canonicalUrl, long elapsedMs) {
        Instant now = Instant.now();
        db.sql("update feed set state = ?, last_http_status = ?, fail_count = 0, notfound_streak = 0, "
                + "last_error = ?, etag = coalesce(?, etag), last_modified_hdr = coalesce(?, last_modified_hdr), "
                + "canonical_url = coalesce(?, canonical_url), last_fetched_at = ?, updated_at = ?, "
                + "avg_fetch_ms = case when avg_fetch_ms is null then ? else (avg_fetch_ms * 3 + ?) / 4 end "
                + "where id = ?")
            .params(state, httpStatus, truncate(error), etag, lastModified, canonicalUrl,
                JdbcTimes.toSql(now), JdbcTimes.toSql(now), (int) elapsedMs, elapsedMs, feedId)
            .update();
    }

    /** Only a real change to the feed's own content moves this, which is what "quiet since" is measured against. */
    public void markChanged(long feedId, Instant changedAt) {
        db.sql("update feed set last_change_at = ? where id = ? and (last_change_at is null or last_change_at < ?)")
            .params(JdbcTimes.toSql(changedAt), feedId, JdbcTimes.toSql(changedAt))
            .update();
    }

    /**
     * Subscriptions the schedule should own. {@code gone} and {@code paused} are excluded because
     * {@link FeedFetchService} refuses to fetch them; {@code error} and {@code muted} are included, since one is a
     * transient state the backoff is supposed to walk out of and the other only silences alerts, not fetching.
     */
    public List<Long> schedulableFeedIds() {
        return db.sql("select id from feed where state in ('active', 'error', 'muted') order by id")
            .query(Long.class)
            .list();
    }

    /** Whether this subscription exists in a state worth queuing — the API's answer to "why is refresh a no-op". */
    public boolean isSchedulable(long feedId) {
        Long count = db.sql("select count(*) from feed where id = ? and state in ('active', 'error', 'muted')")
            .param(feedId)
            .query(Long.class)
            .single();
        return count > 0;
    }

    /** {@code last_error} is VARCHAR(1024). */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
