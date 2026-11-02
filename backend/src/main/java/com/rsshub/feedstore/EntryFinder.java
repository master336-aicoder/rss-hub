package com.rsshub.feedstore;

import com.rsshub.common.JdbcTimes;
import com.rsshub.feedparse.NormalizedEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The inbox reads and the operator's three state flags, which is everything 03 §5.4 leaves untouched: {@code
 * read_state}, {@code starred} and {@code archived} belong to the person reading, never to the publisher.
 *
 * <p>Sorting is always {@code coalesce(published_at, fetched_at) desc, id desc}. A keyset cursor over that pair is
 * what lets page two be correct even while a fetch is inserting rows in the middle of the range — an offset would
 * silently shift the window and show the same entry twice.
 */
@Repository
public class EntryFinder {

    private static final String LIST_COLUMNS = """
        SELECT e.id, e.public_id, e.feed_id,
               coalesce(nullif(f.custom_title, ''), f.title, f.canonical_url) AS feed_title,
               coalesce(f.group_name, '') AS group_name, e.url, e.title, e.summary, e.authors, e.categories,
               e.enclosures, e.published_at, e.updated_at_src, e.fetched_at, e.read_state, e.starred,
               e.archived, e.baseline, e.revision, e.lang,
               coalesce(e.published_at, e.fetched_at) AS sort_key
        """;

    private static final String LIST_FROM = "FROM entry e JOIN feed f ON f.id = e.feed_id";

    private static final RowMapper<EntryRow> ENTRY_ROW = EntryFinder::mapEntry;

    private final JdbcClient db;

    public EntryFinder(JdbcClient db) {
        this.db = db;
    }

    /**
     * One row of the list. The JSON columns arrive as text on purpose — decoding them is a presentation decision,
     * and the list page only shows names.
     */
    public record EntryRow(long id, UUID publicId, long feedId, String feedTitle, String groupName, String url,
        String title, String summary, String authorsJson, String categoriesJson, String enclosuresJson,
        Instant publishedAt, Instant updatedAtSrc, Instant fetchedAt, String readState, boolean starred,
        boolean archived, boolean baseline, int revision, String lang, Instant sortKey) {
    }

    /** The whole row, for the reader pane. {@code contentHtml} is already sanitized (03 §5.5). */
    public record EntryDetail(long id, UUID publicId, long feedId, String feedTitle, String feedSiteUrl, String guid,
        String url, String title, String summary, String contentHtml, String authorsJson, String categoriesJson,
        String enclosuresJson, Instant publishedAt, Instant updatedAtSrc, Instant fetchedAt, String readState,
        boolean starred, boolean archived, boolean baseline, int revision, String lang, String metaJson) {
    }

    /** A revision as the reader pane's history list shows it. Revision 1 is not here; it is the entry row. */
    public record RevisionRow(int revision, String title, String contentHash, Instant updatedAtSrc,
        Instant recordedAt) {
    }

    /**
     * Filters for one page. Only {@code state} is required, and it is the vocabulary of FR-2.4 rather than the column
     * values: {@code unread} excludes the baseline rows FR-2.3 says must never be reported as new.
     *
     * @param alerted FR-2.4's "只看关键词命中": {@code null} does not narrow, {@code TRUE} keeps the entries a rule
     *        shouted about, {@code FALSE} keeps the rest. Three states because a two-state switch would make
     *        "hide the noise" unspellable.
     * @param category one exact category, not a search term
     */
    public record Filter(String state, Long feedId, String groupName, String query, Boolean alerted, String category,
        Instant since, Instant beforeKey, Long beforeId, int limit) {
    }

    public List<EntryRow> list(Filter filter) {
        Sql sql = where(filter);
        String statement = LIST_COLUMNS + LIST_FROM + sql.clause()
            + " ORDER BY sort_key DESC, e.id DESC";
        // The limit is bound, not concatenated: "fetch first ? rows only" is the portable spelling of both dialects.
        return db.sql(statement + " fetch first ? rows only")
            .params(sql.params().toArray())
            .param(filter.limit())
            .query(ENTRY_ROW)
            .list();
    }

    public Optional<EntryDetail> detail(long id) {
        return db.sql("SELECT e.id, e.public_id, e.feed_id, "
                + "coalesce(nullif(f.custom_title, ''), f.title, f.canonical_url) AS feed_title, "
                + "f.site_url AS feed_site_url, e.guid, e.url, e.title, e.summary, e.content_html, e.authors, "
                + "e.categories, e.enclosures, e.published_at, e.updated_at_src, e.fetched_at, e.read_state, "
                + "e.starred, e.archived, e.baseline, e.revision, e.lang, e.meta "
                + "FROM entry e JOIN feed f ON f.id = e.feed_id WHERE e.id = ?")
            .param(id)
            .query(EntryFinder::mapDetail)
            .optional();
    }

    public List<RevisionRow> revisions(long entryId) {
        return db.sql("SELECT revision, title, content_hash, updated_at_src, recorded_at FROM entry_revision "
                + "WHERE entry_id = ? ORDER BY revision DESC")
            .param(entryId)
            .query((ResultSet rs, int rowNum) -> new RevisionRow(
                rs.getInt("revision"),
                rs.getString("title"),
                rs.getString("content_hash"),
                JdbcTimes.fromSql(rs, "updated_at_src"),
                JdbcTimes.fromSql(rs, "recorded_at")))
            .list();
    }

    /**
     * Applies whichever flags the caller sent and returns how many rows the statement matched.
     *
     * <p>Matched, not changed: the two dialects already disagree about that (Postgres counts a row that was written
     * with the value it already held), and a UI counter built on the difference drifts. Re-sending the same state is
     * harmless either way, which is what makes the SPA's "mark read" idempotent when two keys land in the same frame.
     */
    public int applyState(List<Long> ids, String readState, Boolean starred, Boolean archived) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (readState != null) {
            sets.add("read_state = ?");
            params.add(readState);
            // An entry the operator has acted on is no longer baseline-only history (FR-2.3), so it can start
            // counting as unread again on a later revision like any other entry.
            sets.add("baseline = FALSE");
        }
        if (starred != null) {
            sets.add("starred = ?");
            params.add(starred);
        }
        if (archived != null) {
            sets.add("archived = ?");
            params.add(archived);
        }
        if (sets.isEmpty()) {
            return 0;
        }
        // Only the column names above are interpolated, and they are literals this method chooses; every value,
        // including each id, is a bind parameter, so no caller text reaches the statement as SQL.
        StringBuilder placeholders = new StringBuilder();
        for (long id : ids) {
            placeholders.append(placeholders.length() == 0 ? "?" : ", ?");
            params.add(id);
        }
        String statement = "UPDATE entry SET " + String.join(", ", sets)
            + " WHERE id IN (" + placeholders + ")";
        return db.sql(statement).params(params).update();
    }

    /** "Mark the whole feed read" — the one bulk action FR-2.4 needs, bounded by the feed rather than by an id list. */
    public int markFeedRead(long feedId) {
        return db.sql("UPDATE entry SET read_state = 'read', baseline = FALSE WHERE feed_id = ? "
                + "AND read_state = 'unread'").param(feedId).update();
    }

    /** The unread badge counts of FR-2.4's three tabs, in one pass. */
    public Counters counters() {
        return db.sql("""
            SELECT count(*) AS total,
                   COALESCE(SUM(CASE WHEN read_state = 'unread' AND baseline = FALSE THEN 1 ELSE 0 END), 0) AS unread,
                   COALESCE(SUM(CASE WHEN starred THEN 1 ELSE 0 END), 0) AS starred,
                   COALESCE(SUM(CASE WHEN archived THEN 1 ELSE 0 END), 0) AS archived
            FROM entry
            """)
            .query((ResultSet rs, int rowNum) -> new Counters(
                rs.getLong("total"),
                rs.getLong("unread"),
                rs.getLong("starred"),
                rs.getLong("archived")))
            .single();
    }

    /** Baseline rows are excluded from the unread tally exactly as FR-2.3 asks; the rest are plain column counts. */
    public record Counters(long total, long unread, long starred, long archived) {
    }

    /**
     * One dashboard card row. {@code publishedAt} is the coalesced {@code (published_at, fetched_at)} the wall
     * screen sorts by: a card that displayed "no date" for sources that omit {@code pubDate} would look broken.
     */
    public record CardEntry(long id, long feedId, String title, String url, Instant publishedAt, String readState) {
    }

    /**
     * The dashboard's one query (FR-8.1): the newest N per feed in a single statement, because N+1 queries on a
     * page the operator leaves running all day is N+1 chances to show a half-loaded wall. {@code row_number()} is
     * the standard window spelling and runs identically on H2 2.x and PostgreSQL.
     */
    public List<CardEntry> latestPerFeed(int perFeed) {
        return db.sql("""
            SELECT id, feed_id, title, url,
                   coalesce(published_at, fetched_at) AS published_at, read_state
            FROM (
                SELECT id, feed_id, title, url, published_at, fetched_at, read_state,
                       row_number() OVER (PARTITION BY feed_id
                                          ORDER BY coalesce(published_at, fetched_at) DESC, id DESC) AS rn
                FROM entry
            ) ranked
            WHERE ranked.rn <= ?
            ORDER BY feed_id, rn
            """)
            .param(perFeed)
            .query((ResultSet rs, int rowNum) -> new CardEntry(
                rs.getLong("id"),
                rs.getLong("feed_id"),
                rs.getString("title"),
                rs.getString("url"),
                JdbcTimes.fromSql(rs, "published_at"),
                rs.getString("read_state")))
            .list();
    }

    /** The unread badge on each dashboard card; baseline rows stay out of it, same as the inbox's counters. */
    public Map<Long, Long> unreadByFeed() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        db.sql("SELECT feed_id, count(*) AS unread FROM entry WHERE read_state = 'unread' AND baseline = FALSE"
                + " GROUP BY feed_id")
            .query((ResultSet rs, int rowNum) -> Map.entry(rs.getLong("feed_id"), rs.getLong("unread")))
            .list()
            .forEach(pair -> counts.put(pair.getKey(), pair.getValue()));
        return counts;
    }

    /** When the ingest side last touched any entry — the "service" panel's honest answer to "is it working". */
    public Optional<Instant> lastFetchedAt() {
        return db.sql("SELECT max(fetched_at) AS last FROM entry")
            .query((ResultSet rs, int rowNum) -> JdbcTimes.fromSql(rs, "last"))
            .optional();
    }

    /**
     * How many rows arrived in the window the wall calls 今天. This counts by {@code fetched_at}, not
     * {@code published_at}: the question the screen answers is "what did the service bring me", and a source that
     * publishes back-dated items would otherwise make a quiet day look busy and a busy one look quiet.
     */
    public long arrivedSince(Instant since) {
        return db.sql("SELECT count(*) FROM entry WHERE fetched_at >= ?").param(JdbcTimes.toSql(since))
            .query(Long.class).single();
    }

    /** The same arrivals split by the folder the operator filed the source under; no folder is the empty key. */
    public Map<String, Long> arrivedByGroupSince(Instant since) {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.sql("""
            SELECT coalesce(f.group_name, '') AS folder, count(*) AS arrived
            FROM entry e JOIN feed f ON f.id = e.feed_id
            WHERE e.fetched_at >= ?
            GROUP BY coalesce(f.group_name, '')
            ORDER BY count(*) DESC, folder
            """)
            .param(JdbcTimes.toSql(since))
            .query((ResultSet rs, int rowNum) -> Map.entry(rs.getString("folder"), rs.getLong("arrived")))
            .list()
            .forEach(pair -> counts.put(pair.getKey(), pair.getValue()));
        return counts;
    }

    /**
     * Arrivals bucketed by hour, oldest first, for the wall's 24-hour histogram. An hour with nothing in it is
     * absent rather than a zero row: the screen draws the gaps, and a query that manufactured them would need a
     * calendar function neither dialect spells the same way.
     * <p>
     * {@code date_trunc} is the one spelling both H2 2.x and PostgreSQL accept on a
     * {@code TIMESTAMP WITH TIME ZONE}, and the {@code GROUP BY} repeats the expression because only MySQL lets an
     * alias stand there.
     */
    public Map<Instant, Long> arrivalsByHour(Instant since) {
        Map<Instant, Long> buckets = new LinkedHashMap<>();
        // The alias is hour_start, not hour: HOUR is a reserved word in H2 2.x, and an unquoted alias there is a
        // syntax error rather than a column name.
        db.sql("""
            SELECT date_trunc('hour', fetched_at) AS hour_start, count(*) AS arrived
            FROM entry WHERE fetched_at >= ?
            GROUP BY date_trunc('hour', fetched_at)
            ORDER BY date_trunc('hour', fetched_at)
            """)
            .param(JdbcTimes.toSql(since))
            .query((ResultSet rs, int rowNum) ->
                Map.entry(JdbcTimes.fromSql(rs, "hour_start"), rs.getLong("arrived")))
            .list()
            .forEach(pair -> buckets.put(pair.getKey(), pair.getValue()));
        return buckets;
    }

    private Sql where(Filter filter) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        switch (filter.state() == null ? "all" : filter.state()) {
            case "unread" -> {
                conditions.add("e.read_state = 'unread'");
                conditions.add("e.baseline = FALSE");
            }
            case "read" -> conditions.add("e.read_state = 'read'");
            case "dismissed" -> conditions.add("e.read_state = 'dismissed'");
            case "starred" -> conditions.add("e.starred = TRUE");
            case "archived" -> conditions.add("e.archived = TRUE");
            default -> {
                // "all" still hides archived: FR-2.4's archive is a separate shelf, not a filter you can forget.
                conditions.add("e.archived = FALSE");
            }
        }
        if (filter.feedId() != null) {
            conditions.add("e.feed_id = ?");
            params.add(filter.feedId());
        }
        if (filter.groupName() != null && !filter.groupName().isBlank()) {
            conditions.add("f.group_name = ?");
            params.add(filter.groupName());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            // lower()/like rather than ilike or to_tsvector: this has to run identically on H2 and Postgres, and the
            // PG-only full-text column is M6's problem (03 §14). A contains-match on title and summary is enough for
            // a few thousand rows.
            // An operator typing "50%" or "a_b" means those characters, not a wildcard: without the escape the
            // search box can only ever widen a result set, never narrow it.
            String like = '%' + escapeLike(filter.query().toLowerCase(Locale.ROOT)) + '%';
            conditions.add("(lower(coalesce(e.title, '')) LIKE ? ESCAPE '\\' OR lower(coalesce(e.summary, ''))"
                + " LIKE ? ESCAPE '\\')");
            params.add(like);
            params.add(like);
        }
        if (filter.alerted() != null) {
            // The row's red badge and this filter read the same table (rules' notification_log), so an entry can
            // never show a mark the filter hides, or pass the filter without one. No Java dependency is involved:
            // feedstore reads the row set, the rules module still owns writing it.
            conditions.add((filter.alerted() ? "EXISTS" : "NOT EXISTS")
                + " (SELECT 1 FROM notification_log l WHERE l.entry_id = e.id)");
        }
        if (filter.category() != null && !filter.category().isBlank()) {
            // Categories live as one JSON array per row (03 §3), so the term to match is the array element as the
            // codec writes it — quotes and all. Matching the quoted token is what keeps "a" out of a row whose
            // category is "ab"; a bare word would land inside that row's text.
            //
            // The token goes through the same LIKE-escape as a search term and the statement declares ESCAPE '\':
            // a token can itself contain a backslash (an escaped quote in the category name), and without the
            // clause the two dialects would disagree about what that backslash means.
            String like = '%' + escapeLike(EntryCodec.categoryToken(filter.category().trim())) + '%';
            conditions.add("e.categories LIKE ? ESCAPE '\\'");
            params.add(like);
        }
        if (filter.since() != null) {
            conditions.add("coalesce(e.published_at, e.fetched_at) >= ?");
            params.add(JdbcTimes.toSql(filter.since()));
        }
        if (filter.beforeKey() != null && filter.beforeId() != null) {
            conditions.add("(coalesce(e.published_at, e.fetched_at) < ? "
                + "OR (coalesce(e.published_at, e.fetched_at) = ? AND e.id < ?))");
            Object key = JdbcTimes.toSql(filter.beforeKey());
            params.add(key);
            params.add(key);
            params.add(filter.beforeId());
        }
        return new Sql(" WHERE " + String.join(" AND ", conditions), params);
    }

    private record Sql(String clause, List<Object> params) {
    }

    /** The escape character is escaped first, or it would escape its own insertion. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static EntryRow mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new EntryRow(
            rs.getLong("id"),
            rs.getObject("public_id", UUID.class),
            rs.getLong("feed_id"),
            rs.getString("feed_title"),
            rs.getString("group_name"),
            rs.getString("url"),
            rs.getString("title"),
            rs.getString("summary"),
            rs.getString("authors"),
            rs.getString("categories"),
            rs.getString("enclosures"),
            JdbcTimes.fromSql(rs, "published_at"),
            JdbcTimes.fromSql(rs, "updated_at_src"),
            JdbcTimes.fromSql(rs, "fetched_at"),
            rs.getString("read_state"),
            rs.getBoolean("starred"),
            rs.getBoolean("archived"),
            rs.getBoolean("baseline"),
            rs.getInt("revision"),
            rs.getString("lang"),
            JdbcTimes.fromSql(rs, "sort_key"));
    }

    private static EntryDetail mapDetail(ResultSet rs, int rowNum) throws SQLException {
        return new EntryDetail(
            rs.getLong("id"),
            rs.getObject("public_id", UUID.class),
            rs.getLong("feed_id"),
            rs.getString("feed_title"),
            rs.getString("feed_site_url"),
            rs.getString("guid"),
            rs.getString("url"),
            rs.getString("title"),
            rs.getString("summary"),
            rs.getString("content_html"),
            rs.getString("authors"),
            rs.getString("categories"),
            rs.getString("enclosures"),
            JdbcTimes.fromSql(rs, "published_at"),
            JdbcTimes.fromSql(rs, "updated_at_src"),
            JdbcTimes.fromSql(rs, "fetched_at"),
            rs.getString("read_state"),
            rs.getBoolean("starred"),
            rs.getBoolean("archived"),
            rs.getBoolean("baseline"),
            rs.getInt("revision"),
            rs.getString("lang"),
            rs.getString("meta"));
    }

    /** Decoded for the reader pane: the same shape the parser produced, so the UI never sees raw JSON text. */
    public static List<NormalizedEntry.Author> authors(String json) {
        return EntryCodec.readAuthors(json);
    }

    public static List<String> categories(String json) {
        return EntryCodec.readCategories(json);
    }

    public static List<NormalizedEntry.Enclosure> enclosures(String json) {
        return EntryCodec.readEnclosures(json);
    }

    public static String guidSource(String metaJson) {
        return EntryCodec.readGuidSource(metaJson);
    }
}
