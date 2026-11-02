package com.rsshub.events;

import com.rsshub.common.ApiException;
import com.rsshub.feedrender.PublishProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's side of an event stream: create it, rename it, decide whether it also sits in the inbox, delete it.
 * The machine's side is {@link EventIngestService}; the split matters because the two are authenticated
 * completely differently (session versus bearer token) and only the slug is shared between them.
 */
@Service
public class EventStreamService {

    /** Slugs go into a public URL path, so they stay ASCII and short even when the title is Chinese. */
    private static final String SLUG = "[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?";

    public record View(long id, String slug, String title, String description, String visibility, long itemCount,
        String feedUrlBase) {
    }

    private final EventStreamRepository streams;
    private final EventInboxBridge bridge;
    private final PublishProperties publish;

    public EventStreamService(EventStreamRepository streams, EventInboxBridge bridge, PublishProperties publish) {
        this.streams = streams;
        this.bridge = bridge;
        this.publish = publish;
    }

    public List<View> list() {
        return streams.list().stream()
            .map(stream -> new View(stream.id(), stream.slug(), stream.title(), stream.description(),
                stream.visibility(), streams.countItems(stream.id()), publish.baseUrl()))
            .toList();
    }

    public View get(String slug) {
        EventStreamRepository.Stream stream = streams.require(slug);
        return new View(stream.id(), stream.slug(), stream.title(), stream.description(), stream.visibility(),
            streams.countItems(stream.id()), publish.baseUrl());
    }

    @Transactional
    public View create(String slug, String title, String description) {
        String normalized = normalizeSlug(slug);
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("title_required", "An event stream needs a title");
        }
        if (streams.find(normalized).isPresent()) {
            throw ApiException.conflict("stream_exists", "An event stream named " + normalized + " already exists");
        }
        streams.insertStream(normalized, title.trim(), blankToNull(description));
        return get(normalized);
    }

    @Transactional
    public View update(String slug, String title, String description) {
        EventStreamRepository.Stream stream = streams.require(slug);
        // A PATCH sends the fields it means to change, so an absent one keeps its stored value rather than wiping it.
        String newTitle = title == null || title.isBlank() ? stream.title() : title.trim();
        String newDescription = description == null ? stream.description() : blankToNull(description);
        streams.renameStream(stream.id(), newTitle, newDescription);
        return get(slug);
    }

    /** Used by the ingest path for the default stream, where a CI job must not have to create anything first. */
    @Transactional
    public EventStreamRepository.Stream ensureStream(String slug, String title) {
        String normalized = normalizeSlug(slug);
        return streams.find(normalized).orElseGet(() -> {
            streams.insertStream(normalized, title, null);
            return streams.require(normalized);
        });
    }

    /**
     * Turning the inbox on backfills what is already stored — a stream that only publishes from now on would leave
     * the operator's own history invisible, which is the opposite of why they flipped the switch.
     */
    @Transactional
    public View setVisibility(String slug, String visibility) {
        EventStreamRepository.Stream stream = streams.require(slug);
        if (!"internal".equals(visibility) && !"virtual_feed".equals(visibility)) {
            throw ApiException.badRequest("unknown_visibility",
                "visibility is internal or virtual_feed, not " + visibility);
        }
        if (visibility.equals(stream.visibility())) {
            return get(slug);
        }
        streams.setVisibility(stream.id(), visibility);
        if ("virtual_feed".equals(visibility)) {
            bridge.backfill(stream, streams.items(stream.slug(), Integer.MAX_VALUE), this::eventUrl);
        } else {
            bridge.dropFeed(stream.slug());
        }
        return get(slug);
    }

    @Transactional
    public void delete(String slug) {
        EventStreamRepository.Stream stream = streams.require(slug);
        streams.delete(stream.id());
        bridge.dropFeed(stream.slug());
    }

    /**
     * The event's permanent URL (FR-1.5). The public id, not the row id: an auto-increment in a public guid leaks
     * how many events were ever posted and breaks the moment a stream is rebuilt on another host.
     */
    public String eventUrl(String slug, UUID publicId) {
        return publish.absolute("/event/" + slug + "/" + publicId);
    }

    public EventStreamRepository.Stream require(String slug) {
        return streams.require(slug);
    }

    private static String normalizeSlug(String value) {
        String candidate = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!candidate.matches(SLUG)) {
            throw ApiException.badRequest("invalid_slug",
                "A stream slug is lowercase letters, digits and single hyphens, 1-64 characters");
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
