package com.rsshub.feedstore;

import com.rsshub.common.JdbcTimes;
import com.rsshub.feedparse.NormalizedFeed;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The {@code feed} columns the subscription owns: listing for the operator UI, and the metadata a parsed document
 * reports about itself.
 */
@Repository
public class FeedRepository {

    /**
     * One row per subscription, as the UI needs it. The two subqueries are what makes this a single round trip
     * instead of N+1 per feed; {@code scheduled_task} and {@code entry} are both keyed by {@code feed_id}, and at
     * self-hosted volumes (tens of feeds) the planner does the rest.
     */
    private static final String ADMIN_SELECT = """
        SELECT f.id, f.public_id, f.canonical_url, f.requested_url, f.self_url, f.site_url, f.title,
               f.custom_title, f.description, f.language, f.format, f.group_name, f.state, f.fail_count,
               f.notfound_streak, f.last_http_status, f.last_error, f.last_fetched_at, f.last_change_at,
               f.avg_fetch_ms, f.ttl_minutes, f.fetch_interval_minutes, f.created_at,
               (SELECT min(t.fire_at) FROM scheduled_task t
                 WHERE t.kind = 'fetch' AND t.ref_id = f.id AND t.state IN ('pending', 'running')) AS next_fetch_at,
               (SELECT count(*) FROM entry e WHERE e.feed_id = f.id AND e.read_state = 'unread'
                    AND e.baseline = FALSE) AS unread_count,
               (SELECT count(*) FROM entry e WHERE e.feed_id = f.id) AS entry_count
        """;

    /** Split from the select list so "the whole table" and "one row" are the same query with the same columns. */
    private static final String ADMIN_FROM = "FROM feed f";

    /** The operator's label when there is one, else the site's own, else the address. */
    private static final String DISPLAY_TITLE =
        "coalesce(nullif(f.custom_title, ''), f.title, f.canonical_url)";

    private static final String ADMIN_ORDER = "ORDER BY f.group_name, " + DISPLAY_TITLE + ", f.id";

    private static final RowMapper<AdminFeed> ADMIN_ROW = FeedRepository::mapAdmin;

    private final JdbcClient jdbc;

    public FeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A subscription plus the three numbers the sidebar and the health page are made of. {@code nextFetchAt} is null
     * when nothing is queued — a {@code gone} feed, or one added before the queue existed.
     *
     * <p>{@code title}/{@code ttlMinutes} and {@code customTitle}/{@code fetchIntervalMinutes} are the same two
     * facts held by two owners: the first pair is what the publisher reports and is overwritten by every fetch,
     * the second is what the operator typed and nothing but the operator changes. Read the second to display, the
     * first to explain where the feed came from.
     */
    public record AdminFeed(long id, UUID publicId, String canonicalUrl, String requestedUrl, String selfUrl,
        String siteUrl, String title, String customTitle, String description, String language, String format,
        String groupName, String state, int failCount, int notfoundStreak, Integer lastHttpStatus, String lastError,
        Instant lastFetchedAt, Instant lastChangeAt, Integer avgFetchMs, Integer ttlMinutes,
        Integer fetchIntervalMinutes, Instant createdAt, Instant nextFetchAt, long unreadCount, long entryCount) {

        /** The name the operator sees, falling back to the publisher's and then to the address. */
        public String displayTitle() {
            if (customTitle != null && !customTitle.isBlank()) {
                return customTitle;
            }
            return title != null && !title.isBlank() ? title : canonicalUrl;
        }
    }

    public List<AdminFeed> listAdmin() {
        return jdbc.sql(ADMIN_SELECT + ADMIN_FROM + " " + ADMIN_ORDER).query(ADMIN_ROW).list();
    }

    /**
     * The dashboard's feed headers (FR-8.1): one light row per card, so a wall of fifty feeds costs one small
     * query, not the admin list's three subqueries per feed. Every feed appears — an empty card is the honest
     * picture of a source that has not produced anything yet, and a {@code paused} one is dimmed, not hidden.
     *
     * @param nextFetchAt the queued fetch's due time, or null when nothing is armed (a paused or deleted subscription)
     */
    public record FeedCard(long id, String title, String groupName, String state, int failCount, String lastError,
        Instant lastFetchedAt, Instant lastChangeAt, Instant nextFetchAt) {
    }

    public List<FeedCard> listCards() {
        return jdbc.sql("""
                SELECT f.id, coalesce(nullif(f.custom_title, ''), f.title, f.canonical_url) AS title,
                       coalesce(f.group_name, '') AS group_name, f.state, f.fail_count, f.last_error,
                       f.last_fetched_at, f.last_change_at,
                       (SELECT min(t.fire_at) FROM scheduled_task t
                         WHERE t.kind = 'fetch' AND t.ref_id = f.id AND t.state IN ('pending', 'running'))
                           AS next_fetch_at
                FROM feed f
                ORDER BY coalesce(f.group_name, ''),
                         coalesce(nullif(f.custom_title, ''), f.title, f.canonical_url), f.id
                """)
            .query((rs, rowNum) -> new FeedCard(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("group_name"),
                rs.getString("state"),
                rs.getInt("fail_count"),
                rs.getString("last_error"),
                JdbcTimes.fromSql(rs, "last_fetched_at"),
                JdbcTimes.fromSql(rs, "last_change_at"),
                JdbcTimes.fromSql(rs, "next_fetch_at")))
            .list();
    }

    /** The status page's feed total. */
    public long count() {
        return jdbc.sql("SELECT count(*) FROM feed").query(Long.class).single();
    }

    public Optional<AdminFeed> findAdmin(long id) {
        return jdbc.sql(ADMIN_SELECT + ADMIN_FROM + " WHERE f.id = ? " + ADMIN_ORDER)
            .param(id)
            .query(ADMIN_ROW)
            .optional();
    }

    /**
     * Adds a subscription. {@code requestedUrl} is what the operator pasted and {@code canonicalUrl} is what
     * discovery settled on; keeping both is what lets the health page show "the address moved" honestly instead of
     * silently rewriting the subscription.
     */
    public long create(String requestedUrl, String canonicalUrl, String groupName) {
        Instant now = Instant.now();
        // public_id is left out on purpose: both dialects default it (RANDOM_UUID / gen_random_uuid), and naming a
        // generator here would make the insert dialect-specific for no gain.
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("insert into feed (canonical_url, requested_url, group_name, state, created_at, updated_at) "
                + "values (?, ?, ?, 'active', ?, ?)")
            .params(cut(canonicalUrl, 2048), cut(requestedUrl, 2048), cut(groupName, 128),
                JdbcTimes.toSql(now), JdbcTimes.toSql(now))
            // The identity has to be named: H2 answers getGeneratedKeys() with every column that has a default
            // (id, public_id, created_at, updated_at) and GeneratedKeyHolder.getKey() then refuses.
            .update(key, "id");
        Number generated = key.getKey();
        if (generated == null) {
            throw new IllegalStateException("the feed insert returned no identity");
        }
        return generated.longValue();
    }

    public boolean urlExists(String canonicalUrl) {
        Long count = jdbc.sql("select count(*) from feed where canonical_url = ? or requested_url = ?")
            .params(canonicalUrl, canonicalUrl)
            .query(Long.class)
            .single();
        return count > 0;
    }

    /**
     * Partial update of the operator's own columns: a null argument means "leave the column alone", which is why
     * this is coalesces and separate statements rather than a read-modify-write. Two tabs editing one feed must not
     * lose the second change.
     *
     * <p>Three of these columns have three meanings each — absent is "unchanged", the empty string (or {@code 0} for
     * the interval) is "stop overriding", and a value is a value. {@code coalesce} only knows two, so each of them
     * needs its own statement with {@code nullif}: writing {@code ''} into {@code group_name} would leave a second
     * spelling of "no group" that the group picker lists as a blank entry, and the same for a title that silently
     * wins over the site's own.
     *
     * <p>{@code ttl_minutes} is deliberately absent. It is the publisher's report, written by
     * {@link #recordMetadata}; the operator's cadence lives in {@code fetch_interval_minutes} so the two owners stop
     * overwriting each other's column.
     */
    public void update(long id, String groupName, String state, String customTitle, Integer intervalMinutes) {
        Instant now = Instant.now();
        if (state != null) {
            jdbc.sql("update feed set state = ?, updated_at = ? where id = ?")
                .params(state, JdbcTimes.toSql(now), id)
                .update();
        }
        if (groupName != null) {
            jdbc.sql("update feed set group_name = nullif(?, ''), updated_at = ? where id = ?")
                .params(cut(groupName.trim(), 128), JdbcTimes.toSql(now), id)
                .update();
        }
        if (customTitle != null) {
            jdbc.sql("update feed set custom_title = nullif(?, ''), updated_at = ? where id = ?")
                .params(cut(customTitle.trim(), 512), JdbcTimes.toSql(now), id)
                .update();
        }
        if (intervalMinutes != null) {
            jdbc.sql("update feed set fetch_interval_minutes = nullif(?, 0), updated_at = ? where id = ?")
                .params(intervalMinutes, JdbcTimes.toSql(now), id)
                .update();
        }
    }

    /** Clears the operator's own mute/pause so the seeder and the fetcher treat the feed as new again. */
    public void setState(long id, String state) {
        jdbc.sql("update feed set state = ?, updated_at = ? where id = ?")
            .params(state, JdbcTimes.toSql(Instant.now()), id)
            .update();
    }

    /**
     * {@code entry}, {@code entry_revision} and {@code raw_response} go with the feed through {@code ON DELETE
     * CASCADE}, which both dialects declare. {@code scheduled_task} has no foreign key at all (03 §3), so its row is
     * the only one that has to be taken out by hand — and it must be, or the seeder would re-create work for a feed
     * that no longer exists.
     */
    public void delete(long id) {
        jdbc.sql("delete from scheduled_task where kind = 'fetch' and ref_id = ?").param(id).update();
        jdbc.sql("delete from feed where id = ?").param(id).update();
    }

    /**
     * Everything the operator has, in the shape OPML wants: one row per subscription with its outline path. The
     * export carries the label the operator chose, because an OPML file is their list, not the sites' self-report.
     */
    public List<Subscription> listSubscriptions() {
        return jdbc.sql("select canonical_url, site_url, "
                + "coalesce(nullif(custom_title, ''), title) as title, group_name from feed "
                + "order by group_name, coalesce(nullif(custom_title, ''), title, canonical_url), id")
            .query((ResultSet rs, int rowNum) -> new Subscription(
                rs.getString("canonical_url"),
                rs.getString("site_url"),
                rs.getString("title"),
                rs.getString("group_name")))
            .list();
    }

    /**
     * An OPML outline. {@code xmlUrl} is the feed and {@code htmlUrl} the site it belongs to — 03 §5.1 keeps feed
     * and site URLs apart for exactly this reason, and a reader that is shown the feed URL as the human link opens
     * XML in the browser.
     */
    public record Subscription(String xmlUrl, String htmlUrl, String title, String groupName) {
    }

    /**
     * The columns the document itself owns, written once per parsed feed. Every value is coalesced: a publisher that
     * leaves the description out of one response must not empty the column we filled from the last one.
     *
     * <p>{@code image_url} is not written here at all — 03 §5.1's normalised model has no feed image on purpose, and
     * a channel logo is a rendering detail for the publish side, not something a subscription records.
     */
    public void recordMetadata(long feedId, NormalizedFeed feed) {
        Instant now = Instant.now();
        jdbc.sql("update feed set title = coalesce(?, title), site_url = coalesce(?, site_url), "
                + "description = coalesce(?, description), language = coalesce(?, language), "
                + "format = coalesce(?, format), ttl_minutes = coalesce(?, ttl_minutes), "
                + "self_url = coalesce(?, self_url), updated_at = ? where id = ?")
            .params(cut(feed.title(), 512), cut(feed.siteUrl(), 2048), cut(feed.description(), 2048),
                cut(feed.language(), 32), feed.format().stored(), feed.ttlMinutes(),
                cut(feed.feedUrl(), 2048), JdbcTimes.toSql(now), feedId)
            .update();
    }

    /** Titles and URLs the operator can edit by hand; everything else in {@code feed} is the publisher's to report. */
    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static AdminFeed mapAdmin(ResultSet rs, int rowNum) throws SQLException {
        return new AdminFeed(
            rs.getLong("id"),
            rs.getObject("public_id", UUID.class),
            rs.getString("canonical_url"),
            rs.getString("requested_url"),
            rs.getString("self_url"),
            rs.getString("site_url"),
            rs.getString("title"),
            rs.getString("custom_title"),
            rs.getString("description"),
            rs.getString("language"),
            rs.getString("format"),
            rs.getString("group_name"),
            rs.getString("state"),
            rs.getInt("fail_count"),
            rs.getInt("notfound_streak"),
            rs.getObject("last_http_status", Integer.class),
            rs.getString("last_error"),
            instant(rs, "last_fetched_at"),
            instant(rs, "last_change_at"),
            rs.getObject("avg_fetch_ms", Integer.class),
            rs.getObject("ttl_minutes", Integer.class),
            rs.getObject("fetch_interval_minutes", Integer.class),
            instant(rs, "created_at"),
            instant(rs, "next_fetch_at"),
            rs.getLong("unread_count"),
            rs.getLong("entry_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
