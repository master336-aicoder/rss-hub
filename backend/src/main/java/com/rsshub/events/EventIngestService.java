package com.rsshub.events;

import com.rsshub.common.Hashing;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-3.1/3.2: one machine writes here, and it may write the same event twice.
 *
 * <p>The dedupe key is the caller's, so a CI job that retries a failed POST is idempotent by construction. When the
 * caller has no key, the content hash becomes one — which is the honest fallback and also the reason the
 * {@code (stream_id, dedupe_key)} unique index, not application logic, is what actually prevents a duplicate.
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);

    /** The label the auto-created default stream gets; the operator can rename it from the UI. */
    private static final String DEFAULT_STREAM_TITLE = "系统事件";

    /** What the token-authenticated endpoint posts; field names are the contract in 03 §10. */
    public record IngestRequest(String title, String url, String summary, List<String> tags, Instant publishedAt,
        String dedupeKey) {
    }

    public record Stored(String slug, java.util.UUID publicId, boolean created) {
    }

    private final EventStreamRepository streams;
    private final EventInboxBridge bridge;
    private final EventStreamService service;

    public EventIngestService(EventStreamRepository streams, EventInboxBridge bridge, EventStreamService service) {
        this.streams = streams;
        this.bridge = bridge;
        this.service = service;
    }

    /**
     * @param createIfMissing only the default stream gets this: a CI job pointed at {@code /api/ingest/events}
     *     should work before anyone has opened the UI. A slug named in the path must already exist, because a typo
     *     there would otherwise scatter events into streams nobody will ever read.
     */
    @Transactional
    public Stored ingest(String slug, IngestRequest request, boolean createIfMissing) {
        EventStreamRepository.Stream stream = createIfMissing
            ? service.ensureStream(slug, DEFAULT_STREAM_TITLE)
            : streams.require(slug);
        // Everything below reports stream.slug(), not the caller's string: normalisation can rewrite it, and the
        // default stream may have been minted a moment ago.
        if (request.title() == null || request.title().isBlank()) {
            throw com.rsshub.common.ApiException.badRequest("title_required", "An event needs a title");
        }
        Instant publishedAt = request.publishedAt() == null ? Instant.now() : request.publishedAt();
        String contentHash = contentHash(request);
        String dedupeKey = request.dedupeKey() == null || request.dedupeKey().isBlank()
            ? "hash:" + contentHash : request.dedupeKey().trim();
        String tagsJson = EventJson.listJson(request.tags());

        Optional<EventStreamRepository.Item> existing = streams.findItem(stream.id(), dedupeKey);
        if (existing.isPresent()) {
            EventStreamRepository.Item item = existing.get();
            // A replay of the identical body is a no-op: no touch, no unread bump, no new revision.
            if (contentHash.equals(item.contentHash())) {
                return new Stored(stream.slug(), item.publicId(), false);
            }
            streams.updateItem(item.id(), request.title().trim(), trimToNull(request.url()),
                trimToNull(request.summary()), tagsJson, publishedAt, contentHash);
            syncInbox(stream, item.publicId());
            return new Stored(stream.slug(), item.publicId(), false);
        }

        streams.insertItem(stream.id(), dedupeKey, request.title().trim(), trimToNull(request.url()),
            trimToNull(request.summary()), tagsJson, publishedAt, contentHash);
        EventStreamRepository.Item inserted = streams.findItem(stream.id(), dedupeKey)
            .orElseThrow(() -> com.rsshub.common.ApiException.internal("event_lost",
                "Inserted an event into " + stream.slug() + " but cannot read it back"));
        syncInbox(stream, inserted.publicId());
        log.debug("event stream {} stored {} ({})", stream.slug(), inserted.publicId(),
            contentHash.substring(0, 8));
        return new Stored(stream.slug(), inserted.publicId(), true);
    }

    /** Only a bridged stream writes into the inbox; an internal one stays a pure output feed. */
    private void syncInbox(EventStreamRepository.Stream stream, java.util.UUID publicId) {
        if (!"virtual_feed".equals(stream.visibility())) {
            return;
        }
        long feedId = bridge.ensureFeed(stream.slug(), stream.title());
        EventStreamRepository.Item item = streams.findItemByPublicId(publicId)
            .orElseThrow(() -> com.rsshub.common.ApiException.internal("event_lost",
                "Event " + publicId + " disappeared mid-ingest"));
        bridge.upsertEntry(feedId, stream.title(), item, service.eventUrl(stream.slug(), publicId), true);
    }

    /**
     * Title and body only, whitespace-collapsed — the same rule {@code feedstore} uses for real entries, so
     * "did this event change?" means the same thing on both sides of the app.
     */
    private static String contentHash(IngestRequest request) {
        return Hashing.sha256Hex(collapse(request.title()) + "\n" + collapse(request.summary()));
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
