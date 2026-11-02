package com.rsshub.changelog;

import com.rsshub.common.Hashing;
import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code content_change} table (03 §3.6bis): one row per published-side edit, written by the editorial service
 * and read by the changelog outlet and the editor's history panel.
 *
 * <p>Rows are snapshots, not joins. The title, slug and absolute URL are copied in at write time and there is no
 * foreign key to {@code post}, so a deleted post keeps its history and a renamed one keeps what it was called at the
 * time — which is the whole point of announcing changes rather than current state.
 */
@Repository
public class ChangeLogRepository {

    /** The four things that happen to a post, and the only words the outlet publishes about them. */
    public static final String CREATED = "created";
    public static final String UPDATED = "updated";
    public static final String STATUS = "status";
    public static final String DELETED = "deleted";

    /**
     * @param url the post's permalink as it stood when the change happened, already absolute
     */
    public record Change(long id, UUID publicId, long postId, String slug, String title, String url, String action,
        String fromStatus, String toStatus, Instant changedAt) {
    }

    /** Row count, newest stamp and id digest over the rows an outlet would actually carry (03 §6.4). */
    public record FeedStats(long rowCount, Instant newestUpdated, String idDigest) {
    }

    /**
     * One candidate announcement, as the editorial service sees it. The outlet publishes some of these and drops the
     * rest — see {@link #recordIfPublic}.
     *
     * @param url          the post's permalink, already absolute (the caller owns the URL space)
     * @param publishedAt  the post's publication stamp after the change, needed to judge a {@code scheduled} side
     */
    public record ChangeDraft(long postId, String slug, String title, String url, String action, String fromStatus,
        String toStatus, Instant publishedAt) {
    }

    private static final String COLUMNS = """
        select id, public_id, post_id, post_slug, post_title, post_url, action, from_status, to_status, changed_at
        """;

    private static final RowMapper<Change> CHANGE_ROW = (ResultSet rs, int rowNum) -> new Change(
        rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getLong("post_id"), rs.getString("post_slug"),
        rs.getString("post_title"), rs.getString("post_url"), rs.getString("action"), rs.getString("from_status"),
        rs.getString("to_status"), JdbcTimes.fromSql(rs, "changed_at"));

    private final JdbcClient db;

    public ChangeLogRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Writes a change only when at least one side of it is content a reader can actually reach, which is what keeps a
     * private draft out of a public outlet: a changelog that announces "New post: &lt;unpublished title&gt;" leaks the
     * title, and a subscriber has no way to tell that the article is not there yet.
     *
     * <p>The scheduled case is the one thing this gives up. A post that goes live because its clock arrived produces
     * no write, so no changelog row — it is announced by the site feed, whose fingerprint counts readable rows, and
     * the changelog stays silent until someone edits it. Recording it would mean future-dating rows and then
     * invalidating them whenever the publish time moves or the post goes back to draft, which is a larger correctness
     * risk than the missing entry.
     */
    public void recordIfPublic(ChangeDraft draft, Instant now) {
        if (!isPublic(draft.fromStatus(), draft.publishedAt(), now)
            && !isPublic(draft.toStatus(), draft.publishedAt(), now)) {
            return;
        }
        record(draft.postId(), draft.slug(), draft.title(), draft.url(), draft.action(), draft.fromStatus(),
            draft.toStatus());
    }

    /**
     * The Java half of the rule {@code PostRepository.VISIBLE} and {@code PublicViewController.isReadable} state for
     * a post; three places write it down because two of them are SQL and a page, and the outlet must not disagree
     * about what counts as public.
     */
    private static boolean isPublic(String status, Instant publishedAt, Instant now) {
        return "published".equals(status) || "unlisted".equals(status)
            || ("scheduled".equals(status) && publishedAt != null && !publishedAt.isAfter(now));
    }

    /**
     * {@code public_id} is left to the column default in both dialects, exactly as {@code PostRepository.create}
     * does for {@code post}: naming a generator would make the insert dialect-specific for no gain.
     */
    public void record(long postId, String slug, String title, String url, String action, String fromStatus,
        String toStatus) {
        db.sql("insert into content_change (post_id, post_slug, post_title, post_url, action, from_status, "
                + "to_status, changed_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
            .params(postId, cut(slug, 255), cut(title, 512), cut(url, 2048), action, cut(fromStatus, 16),
                cut(toStatus, 16), JdbcTimes.toSql(Instant.now()))
            .update();
    }

    /** Newest first, across the whole site. {@code id} breaks a tie because two edits in one second are still ordered. */
    public List<Change> list(int limit) {
        return db.sql(COLUMNS + "from content_change order by id desc fetch first ? rows only")
            .param(limit)
            .query(CHANGE_ROW)
            .list();
    }

    /** One article's own history, which is what the editor's panel and the SPA need. */
    public List<Change> listOfPost(long postId, int limit) {
        return db.sql(COLUMNS + "from content_change where post_id = ? order by id desc fetch first ? rows only")
            .params(postId, limit)
            .query(CHANGE_ROW)
            .list();
    }

    /**
     * The digest is built in Java over the ids the outlet would emit, for the reason 03 §6.4 gives everywhere:
     * {@code LISTAGG} and {@code string_agg} are not the same function, and an ETag seed cannot be one of the things
     * that diverge between dialects.
     */
    public FeedStats feedStats(int limit) {
        List<Stamp> stamps = db.sql("select id, changed_at from content_change order by id desc fetch first ? rows only")
            .param(limit)
            .query((ResultSet rs, int rowNum) -> new Stamp(rs.getLong("id"), JdbcTimes.fromSql(rs, "changed_at")))
            .list();
        if (stamps.isEmpty()) {
            return new FeedStats(0, null, "");
        }
        StringBuilder ids = new StringBuilder();
        stamps.forEach(stamp -> ids.append(stamp.id()).append(','));
        Instant newest = stamps.get(0).changedAt();
        return new FeedStats(stamps.size(), newest, Hashing.sha256Hex(ids.toString()));
    }

    private record Stamp(long id, Instant changedAt) {
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
