package com.rsshub.events;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import com.rsshub.feedstore.EntryNewEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * FR-3.3's second half: a stream whose visibility is {@code virtual_feed} also puts its events in the inbox, so the
 * operator reads them like any other subscription and the M3 rules can match them.
 *
 * <h2>Why a {@code feed} row at all</h2>
 * The inbox, the unread counter and the rule matcher are all keyed on {@code entry.feed_id}, and a real foreign key
 * needs a real parent. So a bridged stream owns one synthetic parent, marked by its requested URL and parked in
 * state {@code paused} — which is precisely the state {@code FeedFetchRepository.schedulableFeedIds()} refuses to
 * schedule. A virtual source therefore cannot be fetched, cannot be probed, and cannot fail: nothing ever asks it
 * for bytes.
 */
@Component
public class EventInboxBridge {

    /** The marker that makes the synthetic parent findable without another column on {@code event_stream}. */
    static final String MARKER = "event:";

    private static final Logger log = LoggerFactory.getLogger(EventInboxBridge.class);
    private static final String GROUP = "事件源";

    private final JdbcClient db;
    private final ApplicationEventPublisher events;

    public EventInboxBridge(JdbcClient db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    /** Idempotent, because turning the visibility on and off again must not orphan rows in either direction. */
    public long ensureFeed(String slug, String title) {
        Optional<Long> existing = db.sql("select id from feed where requested_url = ?")
            .param(MARKER + slug)
            .query(Long.class)
            .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        db.sql("insert into feed (canonical_url, requested_url, format, title, group_name, state, created_at, "
                + "updated_at) values (?, ?, 'internal', ?, ?, 'paused', ?, ?)")
            .params(MARKER + slug, MARKER + slug, title, GROUP, JdbcTimes.toSql(now), JdbcTimes.toSql(now))
            .update();
        return db.sql("select id from feed where requested_url = ?")
            .param(MARKER + slug)
            .query(Long.class)
            .optional()
            .orElseThrow(() -> ApiException.internal("virtual_feed_lost",
                "Inserted the inbox parent for " + slug + " but cannot read it back"));
    }

    /**
     * Deleting the parent takes its entries with it ({@code on delete cascade}), which is the whole reason the
     * bridge never deletes entries one by one when the operator flips a stream back to {@code internal}: read
     * state belongs to the operator, and a flip that keeps the rows would leave half-read history behind a stream
     * that no longer claims to be in the inbox.
     */
    public void dropFeed(String slug) {
        db.sql("delete from feed where requested_url = ?").param(MARKER + slug).update();
    }

    /**
     * The guid is the event's permalink (FR-1.5): it survives a title edit, and re-ingesting the same key updates
     * the row instead of stacking a second unread item.
     *
     * @param feedTitle the label a rule's payload shows; the bridge owns the synthetic feed row but not its name
     * @param announce false for a backfill, which is history arriving rather than news: 03 §8.1's trigger is a new
     *     entry, and replaying a stream's whole past into an alert channel is not what the operator asked for. An
     *     update of a row that already exists never announces either — a revised event is not a new one.
     */
    public void upsertEntry(long feedId, String feedTitle, EventStreamRepository.Item item, String permalink,
        boolean announce) {
        Optional<Long> existing = db.sql("select id from entry where feed_id = ? and guid = ?")
            .params(feedId, permalink)
            .query(Long.class)
            .optional();
        Instant now = Instant.now();
        if (existing.isPresent()) {
            long entryId = existing.get();
            db.sql("update entry set title = ?, url = ?, summary = ?, content_hash = ?, categories = ?, "
                    + "published_at = ?, updated_at_src = ?, fetched_at = ?, revision = revision + 1, "
                    + "baseline = FALSE where id = ?")
                .params(item.title(), item.url(), item.summary(), item.contentHash(),
                    EventJson.listJson(item.tags()), JdbcTimes.toSql(item.publishedAt()),
                    JdbcTimes.toSql(item.updatedAt()), JdbcTimes.toSql(now), entryId)
                .update();
            return;
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into entry (feed_id, guid, url, title, summary, content_html, content_hash, authors, "
                + "categories, enclosures, published_at, updated_at_src, fetched_at, revision, baseline, lang, meta) "
                + "values (?, ?, ?, ?, ?, NULL, ?, '[]', ?, '[]', ?, ?, ?, 1, FALSE, NULL, "
                + "'{\"source\":\"event\"}')")
            .params(feedId, permalink, item.url(), item.title(), item.summary(), item.contentHash(),
                EventJson.listJson(item.tags()), JdbcTimes.toSql(item.publishedAt()),
                JdbcTimes.toSql(item.updatedAt()), JdbcTimes.toSql(now))
            // "id" named because entry has defaulted columns and H2 hands all of them back otherwise.
            .update(key, "id");
        if (announce && key.getKey() != null) {
            events.publishEvent(new EntryNewEvent(key.getKey().longValue(), feedId, feedTitle, GROUP,
                item.url(), item.title(), item.summary(), item.contentHash(), item.tags(), item.publishedAt(), now));
        }
    }

    public void backfill(EventStreamRepository.Stream stream, List<EventStreamRepository.Item> items,
        Permalink permalinks) {
        long feedId = ensureFeed(stream.slug(), stream.title());
        for (EventStreamRepository.Item item : items) {
            upsertEntry(feedId, stream.title(), item, permalinks.of(stream.slug(), item.publicId()), false);
        }
        log.info("event stream {} bridged {} stored items into the inbox", stream.slug(), items.size());
    }

    /** Same collapse the ingest path uses, so a re-ingest and a backfill agree on what "unchanged" means. */
    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /** How an item's permanent URL is built; injected so the bridge does not need the publishing config. */
    public interface Permalink {
        String of(String slug, UUID publicId);
    }
}
