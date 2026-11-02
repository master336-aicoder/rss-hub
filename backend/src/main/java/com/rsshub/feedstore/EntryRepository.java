package com.rsshub.feedstore;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import com.rsshub.feedparse.NormalizedEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The {@code entry} and {@code entry_revision} writes, which is 03 §5.4's idempotent upsert seen from the SQL side.
 * The decision of *what* to write lives in {@link EntryIngestService}; this class only knows the columns.
 *
 * <p>Every statement is written in the subset both dialects accept, because the dev database is H2 and production is
 * Postgres and nothing in this project runs both in CI (03 §14 O13). The column-for-column contract is what
 * {@code MigrationDialectAlignmentTest} enforces.
 */
@Repository
public class EntryRepository {

    /** What the upsert needs to know about a row that already exists. */
    public record Stored(long id, String contentHash, Instant updatedAtSrc, int revision) {
    }

    private static final RowMapper<Stored> STORED_ROW = (ResultSet rs, int rowNum) -> new Stored(
        rs.getLong("id"), rs.getString("content_hash"), JdbcTimes.fromSql(rs, "updated_at_src"),
        rs.getInt("revision"));

    private final JdbcClient db;

    public EntryRepository(JdbcClient db) {
        this.db = db;
    }

    /** The whole point of the {@code (feed_id, guid)} unique index: one lookup per parsed entry per fetch. */
    public Optional<Stored> findStored(long feedId, String guid) {
        return db.sql("select id, content_hash, updated_at_src, revision from entry "
                + "where feed_id = ? and guid = ?")
            .params(feedId, guid)
            .query(STORED_ROW)
            .optional();
    }

    /**
     * "Has this subscription ever yielded an entry?" is what makes the first fetch a baseline instead of a flood of
     * unread items (FR-2.3). A feed whose entries were all deleted still counts as known — the row was there once.
     */
    public boolean hasAnyEntry(long feedId) {
        Integer count = db.sql("select 1 from entry where feed_id = ? limit 1")
            .param(feedId)
            .query(Integer.class)
            .optional()
            .orElse(null);
        return count != null;
    }

    /**
     * @return the new row's id, which {@code notification_log.entry_id} needs (03 §8.2). It is named in the
     *     {@code update(key, "id")} call rather than read back: {@code entry} has defaulted columns, and H2 hands
     *     every one of them to {@code getGeneratedKeys()} unless the caller says which one it wants.
     */
    public long insert(long feedId, NormalizedEntry entry, String contentHash, boolean baseline, Instant fetchedAt) {
        String guid = cut(entry.guid(), 2048);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into entry (feed_id, guid, url, title, summary, content_html, content_hash, authors, "
                + "categories, enclosures, published_at, updated_at_src, fetched_at, revision, baseline, lang, meta) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")
            .params(feedId, guid, cut(entry.url(), 2048), cut(entry.title(), 1024),
                entry.summary(), entry.contentHtml(), contentHash, EntryCodec.authors(entry.authors()),
                EntryCodec.categories(entry.categories()), EntryCodec.enclosures(entry.enclosures()),
                JdbcTimes.toSql(entry.publishedAt()), JdbcTimes.toSql(entry.updatedAt()), JdbcTimes.toSql(fetchedAt),
                baseline, cut(entry.lang(), 32),
                EntryCodec.meta(entry.guidSource(), entry.idIsStable(), entry.extras()))
            .update(key, "id");
        Number generated = key.getKey();
        if (generated == null) {
            throw ApiException.internal("entry_not_stored",
                "Inserted an entry for feed " + feedId + " but the insert returned no identity");
        }
        return generated.longValue();
    }

    /**
     * A revision row for a body that changed. Only changes get a row: revision 1 is already the {@code entry} row
     * itself, and duplicating it here would double the storage of every feed we have ever read.
     */
    public void recordRevision(long entryId, int revision, NormalizedEntry entry, String contentHash,
        Instant recordedAt) {
        db.sql("insert into entry_revision (entry_id, revision, title, content_hash, updated_at_src, raw, recorded_at) "
                + "values (?, ?, ?, ?, ?, ?, ?)")
            .params(entryId, revision, cut(entry.title(), 1024), contentHash, JdbcTimes.toSql(entry.updatedAt()),
                entry.contentHtml(), JdbcTimes.toSql(recordedAt))
            .update();
    }

    /**
     * Moves the main row to the new revision. {@code read_state} and {@code starred} are deliberately not touched:
     * an entry the operator has already read must not come back as unread because the publisher fixed a typo.
     *
     * <p>The source's own update time is coalesced, not replaced — a publisher that stops sending {@code updated}
     * would otherwise erase the only date the "stale revision" rule compares against.
     */
    public void applyRevision(long entryId, int revision, NormalizedEntry entry, String contentHash,
        Instant fetchedAt) {
        db.sql("update entry set url = ?, title = ?, summary = ?, content_html = ?, content_hash = ?, authors = ?, "
                + "categories = ?, enclosures = ?, published_at = ?, updated_at_src = coalesce(?, updated_at_src), "
                + "fetched_at = ?, revision = ?, lang = ?, meta = ? where id = ?")
            .params(cut(entry.url(), 2048), cut(entry.title(), 1024), entry.summary(), entry.contentHtml(),
                contentHash, EntryCodec.authors(entry.authors()), EntryCodec.categories(entry.categories()),
                EntryCodec.enclosures(entry.enclosures()), JdbcTimes.toSql(entry.publishedAt()),
                JdbcTimes.toSql(entry.updatedAt()), JdbcTimes.toSql(fetchedAt), revision, cut(entry.lang(), 32),
                EntryCodec.meta(entry.guidSource(), entry.idIsStable(), entry.extras()), entryId)
            .update();
    }

    /** The widths are 03 §3's DDL. Cutting beats dropping: a guid longer than 2048 chars is still a stable id. */
    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
