package com.rsshub.feedstore;

import com.rsshub.common.Hashing;
import com.rsshub.feedfetch.FeedDocumentProcessor;
import com.rsshub.feedparse.FeedParser;
import com.rsshub.feedparse.NormalizedEntry;
import com.rsshub.feedparse.NormalizedFeed;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 02 §3's seam, implemented: the document arrives as bytes, leaves as rows. This is the only place parsing and
 * storage meet, which is why {@code feedfetch} knows nothing about {@code feedparse}.
 *
 * <p>03 §5.4's upsert rules, in order: a guid we have not seen is inserted (as a baseline row when the subscription
 * is new); a guid whose content hash matches does nothing at all — no timestamp refresh, no unread bump; a changed
 * hash becomes a new revision, unless the source's own update time says the change is older than what is stored, in
 * which case it is dropped so a feed that rewinds its dates cannot make old articles look new forever.
 */
@Service
public class EntryIngestService implements FeedDocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(EntryIngestService.class);
    private static final FeedParser PARSER = new FeedParser();

    private final EntryRepository entries;
    private final FeedRepository feeds;
    private final ApplicationEventPublisher events;

    public EntryIngestService(EntryRepository entries, FeedRepository feeds, ApplicationEventPublisher events) {
        this.entries = entries;
        this.feeds = feeds;
        this.events = events;
    }

    @Override
    @Transactional
    public Result process(Request request) {
        NormalizedFeed parsed = PARSER.parse(request.body(), request.contentType(), request.feedUrl());
        boolean baseline = !entries.hasAnyEntry(request.feedId());
        // The label a rule's payload shows. Read once per document instead of once per entry, and not at all for a
        // baseline load, which cannot publish anything.
        Optional<FeedRepository.AdminFeed> feed =
            baseline ? Optional.empty() : feeds.findAdmin(request.feedId());
        int stored = 0;
        int stale = 0;

        for (NormalizedEntry entry : parsed.entries()) {
            String contentHash = contentHash(entry);
            Optional<EntryRepository.Stored> existing = entries.findStored(request.feedId(), entry.guid());
            if (existing.isEmpty()) {
                long entryId = entries.insert(request.feedId(), entry, contentHash, baseline, request.fetchedAt());
                stored++;
                if (!baseline) {
                    feed.ifPresent(row -> events.publishEvent(newEntry(entryId, row, entry, contentHash,
                        request.fetchedAt())));
                }
                continue;
            }
            EntryRepository.Stored row = existing.get();
            if (contentHash.equals(row.contentHash())) {
                continue;
            }
            if (isOlderThanStored(row.updatedAtSrc(), entry.updatedAt())) {
                stale++;
                continue;
            }
            int revision = row.revision() + 1;
            entries.recordRevision(row.id(), revision, entry, contentHash, request.fetchedAt());
            entries.applyRevision(row.id(), revision, entry, contentHash, request.fetchedAt());
            stored++;
        }

        feeds.recordMetadata(request.feedId(), parsed);
        if (stale > 0) {
            log.info("feed {} ignored {} changed entries whose source update time was older than the stored one",
                request.feedId(), stale);
        }
        log.debug("feed {} baseline={} stored={} format={}", request.feedId(), baseline, stored,
            parsed.format().stored());
        return new Result(parsed.entries().size(), parsed.format().stored(), stored > 0);
    }

    /**
     * 03 §8.1's trigger, published while this transaction is still open so the rule engine's hit rows commit or
     * roll back with the entry itself. A revision is not a new entry and a baseline load is not news, which is why
     * only the insert branch above reaches here.
     */
    private static EntryNewEvent newEntry(long entryId, FeedRepository.AdminFeed feed, NormalizedEntry entry,
        String contentHash, Instant fetchedAt) {
        return new EntryNewEvent(entryId, feed.id(), feed.title() == null ? feed.canonicalUrl() : feed.title(),
            feed.groupName(), entry.url(), entry.title(), entry.summary(), contentHash, entry.categories(),
            entry.publishedAt(), fetchedAt);
    }

    /**
     * 03 §5.4: the hash covers title and body only, whitespace-collapsed. Summary, dates and author lists are
     * excluded on purpose — they change without the article having changed, and treating them as edits would turn
     * a quiet subscription into permanent revision churn.
     */
    private static String contentHash(NormalizedEntry entry) {
        return Hashing.sha256Hex(collapse(entry.title()) + "\n" + collapse(entry.contentHtml()));
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Only a strictly older source date loses. A missing date on either side is not "older" — a publisher that
     * stopped sending {@code updated} must still be able to correct an article, and rejecting it would freeze the
     * entry at whatever we first saw.
     */
    private static boolean isOlderThanStored(Instant stored, Instant incoming) {
        return stored != null && incoming != null && incoming.isBefore(stored);
    }
}
