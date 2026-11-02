package com.rsshub.repack;

import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The three M5 tables (03 §3.11): {@code repack} is one forwarding outlet, {@code repack_source} is one subscribed
 * feed inside it plus that feed's full-content flag, and {@code repack_source_audit} is the only table here with no
 * foreign keys — it has to outlive both of the rows it describes.
 *
 * <p>Rows are read out of {@code entry} exactly as the inbox stores them; deciding what a republished item may carry
 * is {@link RepackFeedSource}'s job, and the operator's side is {@link RepackService}.
 */
@Repository
public class RepackRepository {

    /** One forwarding collection as stored. */
    public record Repack(long id, String slug, String title, String description, Instant createdAt) {
    }

    /** One member feed of a collection, with the numbers the management screen shows next to it. */
    public record Member(long id, long repackId, long feedId, String feedTitle, String canonicalUrl, String groupName,
        String feedState, boolean fullContent, Instant fullContentChangedAt, long entryCount) {
    }

    /** One entry as the outlet sees it: the original's own URL, title and stamps, plus its source's identity. */
    public record Item(long id, UUID publicId, String guid, String url, String title, String summary,
        String contentHtml, List<String> authors, List<String> categories, Instant publishedAt, Instant updatedAt,
        String sourceUrl, String sourceTitle, boolean fullContent) {
    }

    /** The three numbers an ETag seed needs, without reading a single body column (03 §6.4). */
    public record FeedStats(long rowCount, Instant newestUpdated, String idDigest) {
    }

    /** One line of the full-content trail (FR-7.2's audit gate). */
    public record AuditRow(long id, long repackId, long feedId, String action, boolean fullContent, Instant createdAt) {
    }

    /**
     * Newest first, and "dismissed" never ships: the operator hid that entry on purpose, so putting it back on the
     * public wire would be the aggregator overriding the reader.
     */
    private static final String ITEM_SELECT = """
        select e.id, e.public_id, e.guid, e.url, e.title, e.summary, e.content_html, e.authors, e.categories,
               coalesce(e.published_at, e.fetched_at) as published_at,
               coalesce(e.updated_at_src, coalesce(e.published_at, e.fetched_at)) as updated_at,
               f.canonical_url as source_url, f.title as source_title, rs.full_content
        from entry e
        join repack_source rs on rs.feed_id = e.feed_id
        join feed f on f.id = e.feed_id
        where rs.repack_id = (select id from repack where slug = ?)
          and e.read_state <> 'dismissed'
        order by coalesce(e.published_at, e.fetched_at) desc, e.id desc
        fetch first ? rows only
        """;

    private static final RowMapper<Repack> REPACK_ROW = (ResultSet rs, int rowNum) -> new Repack(
        rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("description"),
        JdbcTimes.fromSql(rs, "created_at"));

    private static final RowMapper<Member> MEMBER_ROW = (ResultSet rs, int rowNum) -> new Member(
        rs.getLong("id"), rs.getLong("repack_id"), rs.getLong("feed_id"), rs.getString("feed_title"),
        rs.getString("canonical_url"), rs.getString("group_name"), rs.getString("feed_state"),
        rs.getBoolean("full_content"), JdbcTimes.fromSql(rs, "updated_at"), rs.getLong("entry_count"));

    private static final RowMapper<Item> ITEM_ROW = (ResultSet rs, int rowNum) -> new Item(
        rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getString("guid"), rs.getString("url"),
        rs.getString("title"), rs.getString("summary"), rs.getString("content_html"),
        RepackJson.authorNames(rs.getString("authors")), RepackJson.stringList(rs.getString("categories")),
        JdbcTimes.fromSql(rs, "published_at"), JdbcTimes.fromSql(rs, "updated_at"),
        rs.getString("source_url"), rs.getString("source_title"), rs.getBoolean("full_content"));

    private static final RowMapper<AuditRow> AUDIT_ROW = (ResultSet rs, int rowNum) -> new AuditRow(
        rs.getLong("id"), rs.getLong("repack_id"), rs.getLong("feed_id"), rs.getString("action"),
        rs.getBoolean("full_content"), JdbcTimes.fromSql(rs, "created_at"));

    private final JdbcClient db;

    public RepackRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Repack> list() {
        return db.sql("select " + REPACK_COLUMNS + " from repack order by slug")
            .query(REPACK_ROW)
            .list();
    }

    public Optional<Repack> find(String slug) {
        return db.sql("select " + REPACK_COLUMNS + " from repack where slug = ?")
            .param(slug)
            .query(REPACK_ROW)
            .optional();
    }

    /** A collection nobody created is a 404, never an empty 200 outlet and never a silent no-op PATCH. */
    public Repack require(String slug) {
        return find(slug).orElseThrow(() -> com.rsshub.common.ApiException.notFound("unknown_repack",
            "No repack feed " + slug));
    }

    public void insertRepack(String slug, String title, String description) {
        Instant now = Instant.now();
        db.sql("insert into repack (slug, title, description, created_at, updated_at) values (?, ?, ?, ?, ?)")
            .params(slug, title, description, JdbcTimes.toSql(now), JdbcTimes.toSql(now))
            .update();
    }

    public void updateRepack(long repackId, String title, String description) {
        db.sql("update repack set title = ?, description = ?, updated_at = ? where id = ?")
            .params(title, description, JdbcTimes.toSql(Instant.now()), repackId)
            .update();
    }

    /** The membership rows go with the collection; the audit deliberately stays behind. */
    public void deleteRepack(long repackId) {
        db.sql("delete from repack_source where repack_id = ?").param(repackId).update();
        db.sql("delete from repack where id = ?").param(repackId).update();
    }

    public List<Member> members(long repackId) {
        return db.sql("select " + MEMBER_COLUMNS + " from repack_source rs "
                + "join feed f on f.id = rs.feed_id "
                + "where rs.repack_id = ? "
                + "order by coalesce(f.title, f.canonical_url)")
            .param(repackId)
            .query(MEMBER_ROW)
            .list();
    }

    public Optional<Member> findMember(long repackId, long feedId) {
        return db.sql("select " + MEMBER_COLUMNS + " from repack_source rs "
                + "join feed f on f.id = rs.feed_id "
                + "where rs.repack_id = ? and rs.feed_id = ?")
            .params(repackId, feedId)
            .query(MEMBER_ROW)
            .optional();
    }

    public int countMembers(long repackId) {
        Integer count = db.sql("select count(*) from repack_source where repack_id = ?")
            .param(repackId)
            .query(Integer.class)
            .optional()
            .orElse(0);
        return count;
    }

    public boolean feedExists(long feedId) {
        Integer count = db.sql("select count(*) from feed where id = ?")
            .param(feedId)
            .query(Integer.class)
            .optional()
            .orElse(0);
        return count > 0;
    }

    /** A new member always starts summaries-only: FR-7.2 makes full text an opt-in, so the insert ignores the flag. */
    public void insertMember(long repackId, long feedId) {
        Instant now = Instant.now();
        db.sql("insert into repack_source (repack_id, feed_id, full_content, created_at, updated_at) "
                + "values (?, ?, FALSE, ?, ?)")
            .params(repackId, feedId, JdbcTimes.toSql(now), JdbcTimes.toSql(now))
            .update();
    }

    public void setFullContent(long memberId, boolean fullContent) {
        db.sql("update repack_source set full_content = ?, updated_at = ? where id = ?")
            .params(fullContent, JdbcTimes.toSql(Instant.now()), memberId)
            .update();
    }

    public void deleteMember(long memberId) {
        db.sql("delete from repack_source where id = ?").param(memberId).update();
    }

    public List<Item> items(String slug, int limit) {
        return db.sql(ITEM_SELECT).params(slug, limit).query(ITEM_ROW).list();
    }

    /** Newest rows only, capped like the outlet is: an ETag over the whole archive would change never. */
    public FeedStats feedStats(String slug, int limit) {
        List<Stamp> stamps = db.sql("select e.id, "
                + "coalesce(e.updated_at_src, coalesce(e.published_at, e.fetched_at)) as stamp "
                + "from entry e join repack_source rs on rs.feed_id = e.feed_id "
                + "where rs.repack_id = (select id from repack where slug = ?) and e.read_state <> 'dismissed' "
                + "order by coalesce(e.published_at, e.fetched_at) desc, e.id desc fetch first ? rows only")
            .params(slug, limit)
            .query((rs, rowNum) -> new Stamp(rs.getLong("id"), JdbcTimes.fromSql(rs, "stamp")))
            .list();
        if (stamps.isEmpty()) {
            return new FeedStats(0, null, "");
        }
        Instant newest = stamps.stream().map(Stamp::stamp).max(Instant::compareTo).orElse(null);
        return new FeedStats(stamps.size(), newest, RepackJson.idDigest(stamps.stream().map(Stamp::id).toList()));
    }

    public List<AuditRow> audit(long repackId, int limit) {
        return db.sql("select id, repack_id, feed_id, action, full_content, created_at from repack_source_audit "
                + "where repack_id = ? order by id desc fetch first ? rows only")
            .params(repackId, limit)
            .query(AUDIT_ROW)
            .list();
    }

    public void insertAudit(long repackId, long feedId, String action, boolean fullContent) {
        db.sql("insert into repack_source_audit (repack_id, feed_id, action, full_content, created_at) "
                + "values (?, ?, ?, ?, ?)")
            .params(repackId, feedId, action, fullContent, JdbcTimes.toSql(Instant.now()))
            .update();
    }

    private record Stamp(long id, Instant stamp) {
    }

    private static final String REPACK_COLUMNS = "id, slug, title, description, created_at";

    private static final String MEMBER_COLUMNS = """
        rs.id, rs.repack_id, rs.feed_id, f.title as feed_title, f.canonical_url, f.group_name,
        f.state as feed_state, rs.full_content, rs.updated_at,
        (select count(*) from entry e where e.feed_id = rs.feed_id and e.read_state <> 'dismissed') as entry_count
        """;
}
