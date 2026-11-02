package com.rsshub.events;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two M3 tables (03 §3.7): {@code event_stream} is one machine-publishable outlet, {@code event_item} is one
 * row inside it.
 *
 * <p>Nothing here reaches the network and nothing here renders XML — the outlet is
 * {@link EventFeedSource}'s job, and the write path is {@link EventIngestService}'s. This class only moves rows,
 * which is what lets the same table answer both a token-authenticated POST and an anonymous {@code /feed/x.rss}.
 */
@Repository
public class EventStreamRepository {

    /** One stream as stored. */
    public record Stream(long id, String slug, String title, String description, String visibility,
        Instant createdAt) {
    }

    /** One event. The public id is the permalink, so it is minted by the database and never rewritten. */
    public record Item(long id, UUID publicId, long streamId, String dedupeKey, String title, String url,
        String summary, List<String> tags, Instant publishedAt, Instant updatedAt, String contentHash) {
    }

    /** The three numbers an ETag seed needs, without reading a single body column (03 §6.4). */
    public record FeedStats(long rowCount, Instant newestUpdated, String idDigest) {
    }

    private static final RowMapper<Stream> STREAM_ROW = (ResultSet rs, int rowNum) -> new Stream(
        rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("description"),
        rs.getString("visibility"), JdbcTimes.fromSql(rs, "created_at"));

    private static final RowMapper<Item> ITEM_ROW = (ResultSet rs, int rowNum) -> new Item(
        rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getLong("stream_id"),
        rs.getString("dedupe_key"), rs.getString("title"), rs.getString("url"), rs.getString("summary"),
        EventJson.stringList(rs.getString("tags")),
        JdbcTimes.fromSql(rs, "published_at"), JdbcTimes.fromSql(rs, "updated_at"),
        rs.getString("content_hash"));

    private final JdbcClient db;

    public EventStreamRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Stream> list() {
        return db.sql("select " + STREAM_COLUMNS + " from event_stream order by slug")
            .query(STREAM_ROW)
            .list();
    }

    public Optional<Stream> find(String slug) {
        return db.sql("select " + STREAM_COLUMNS + " from event_stream where slug = ?")
            .param(slug)
            .query(STREAM_ROW)
            .optional();
    }

    public Stream require(String slug) {
        return find(slug).orElseThrow(() -> com.rsshub.common.ApiException.notFound(
            "unknown_event_stream", "No event stream " + slug));
    }

    /**
     * A new stream starts {@code internal}: publishing into the inbox is the operator's decision, not the
     * default a CI job gets by accident (FR-3.3). The stream's own id is its identity here; the inbox link, if any,
     * lives on the synthetic {@code feed} row and is found by its {@code event:{slug}} marker.
     */
    public void insertStream(String slug, String title, String description) {
        db.sql("insert into event_stream (slug, title, description, visibility, created_at, updated_at) "
                + "values (?, ?, ?, 'internal', ?, ?)")
            .params(slug, title, description, JdbcTimes.toSql(Instant.now()), JdbcTimes.toSql(Instant.now()))
            .update();
    }

    /** Only the visible label moves; {@code visibility} flips through {@link #insertStream}'s partner below. */
    public void renameStream(long streamId, String title, String description) {
        db.sql("update event_stream set title = ?, description = ?, updated_at = ? where id = ?")
            .params(title, description, JdbcTimes.toSql(Instant.now()), streamId)
            .update();
    }

    public void setVisibility(long streamId, String visibility) {
        db.sql("update event_stream set visibility = ?, updated_at = ? where id = ?")
            .params(visibility, JdbcTimes.toSql(Instant.now()), streamId)
            .update();
    }

    /** Entries belonging to a deleted stream go with it: {@code feed} cascades to {@code entry}. */
    public void delete(long streamId) {
        db.sql("delete from event_item where stream_id = ?").param(streamId).update();
        db.sql("delete from event_stream where id = ?").param(streamId).update();
    }

    public Optional<Item> findItem(long streamId, String dedupeKey) {
        return db.sql("select " + ITEM_COLUMNS + " from event_item where stream_id = ? and dedupe_key = ?")
            .params(streamId, dedupeKey)
            .query(ITEM_ROW)
            .optional();
    }

    public Optional<Item> findItemByPublicId(UUID publicId) {
        return db.sql("select " + ITEM_COLUMNS + " from event_item where public_id = ?")
            .param(publicId)
            .query(ITEM_ROW)
            .optional();
    }

    public void insertItem(long streamId, String dedupeKey, String title, String url, String summary, String tagsJson,
        Instant publishedAt, String contentHash) {
        db.sql("insert into event_item (stream_id, dedupe_key, title, url, summary, tags, published_at, "
                + "content_hash, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(streamId, dedupeKey, title, url, summary, tagsJson, JdbcTimes.toSql(publishedAt), contentHash,
                JdbcTimes.toSql(Instant.now()), JdbcTimes.toSql(Instant.now()))
            .update();
    }

    /**
     * FR-3.2's "same key updates instead of inserting". {@code updated_at} moves even when only the summary did,
     * because Atom's per-entry {@code <updated>} is mandatory and a stale stamp is a lie to the reader.
     */
    public void updateItem(long itemId, String title, String url, String summary, String tagsJson,
        Instant publishedAt, String contentHash) {
        db.sql("update event_item set title = ?, url = ?, summary = ?, tags = ?, published_at = ?, content_hash = ?, "
                + "updated_at = ? where id = ?")
            .params(title, url, summary, tagsJson, JdbcTimes.toSql(publishedAt), contentHash,
                JdbcTimes.toSql(Instant.now()), itemId)
            .update();
    }

    public void deleteItem(long itemId) {
        db.sql("delete from event_item where id = ?").param(itemId).update();
    }

    public List<Item> items(String slug, int limit) {
        return db.sql("select " + ITEM_COLUMNS + " from event_item where stream_id = (select id from event_stream "
                + "where slug = ?) order by published_at desc, id desc fetch first ? rows only")
            .params(slug, limit)
            .query(ITEM_ROW)
            .list();
    }

    public long countItems(long streamId) {
        Long count = db.sql("select count(*) from event_item where stream_id = ?")
            .param(streamId)
            .query(Long.class)
            .optional()
            .orElse(0L);
        return count;
    }

    /** Newest rows only, capped like the outlet is: an ETag over 100 000 history rows would change never. */
    public FeedStats feedStats(String slug, int limit) {
        List<Stamp> stamps = db.sql("select id, updated_at from event_item where stream_id = "
                + "(select id from event_stream where slug = ?) order by published_at desc, id desc "
                + "fetch first ? rows only")
            .params(slug, limit)
            .query((rs, rowNum) -> new Stamp(rs.getLong("id"), JdbcTimes.fromSql(rs, "updated_at")))
            .list();
        if (stamps.isEmpty()) {
            return new FeedStats(0, null, "");
        }
        Instant newest = stamps.stream().map(Stamp::updatedAt).max(Instant::compareTo).orElse(null);
        String digest = EventJson.idDigest(stamps.stream().map(Stamp::id).toList());
        return new FeedStats(stamps.size(), newest, digest);
    }

    private record Stamp(long id, Instant updatedAt) {
    }

    /**
     * The synthetic {@code feed} row that carries this stream into the inbox (FR-3.3), looked up by the marker the
     * bridge writes. Null means the stream is {@code internal} and has never been bridged.
     */
    public Optional<Long> findInboxFeedId(String slug) {
        return db.sql("select id from feed where requested_url = ?")
            .param(EventInboxBridge.MARKER + slug)
            .query(Long.class)
            .optional();
    }

    private static final String STREAM_COLUMNS =
        "id, slug, title, description, visibility, created_at";

    private static final String ITEM_COLUMNS =
        "id, public_id, stream_id, dedupe_key, title, url, summary, tags, published_at, updated_at, content_hash";
}
