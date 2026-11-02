package com.rsshub.events;

import com.rsshub.common.ApiException;
import com.rsshub.feedrender.FeedFingerprint;
import com.rsshub.feedrender.FeedItem;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedMeta;
import com.rsshub.feedrender.FeedScope;
import com.rsshub.feedrender.FeedSource;
import com.rsshub.feedrender.PublishProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /feed/{slug}.{format}} (03 §6.2's {@code EVENTS} scope): the operator's own machine events, published
 * outward so a status page, a phone reader or a colleague's aggregator can subscribe to them.
 *
 * <p>It is a {@link FeedSource} and nothing more — one bean plus the path rule that already existed, which is why
 * adding this outlet changed no writer, no renderer and no controller (03 §6.1).
 */
@Component
public class EventFeedSource implements FeedSource {

    private final EventStreamRepository streams;
    private final EventStreamService service;
    private final PublishProperties publish;

    public EventFeedSource(EventStreamRepository streams, EventStreamService service, PublishProperties publish) {
        this.streams = streams;
        this.service = service;
        this.publish = publish;
    }

    @Override
    public FeedScope scope() {
        return FeedScope.EVENTS;
    }

    @Override
    public FeedMeta meta(FeedKey key) {
        EventStreamRepository.Stream stream = require(key.slug());
        // RSS 2.0's channel description is mandatory, so a stream created without one states what it holds.
        String description = stream.description() == null || stream.description().isBlank()
            ? "Machine events published by " + stream.title()
            : stream.description();
        return new FeedMeta(stream.title(), publish.absolute("/"), description, publish.language(),
            publish.ttlMinutes(), null, null, null, null);
    }

    @Override
    public List<FeedItem> items(FeedKey key, int limit) {
        require(key.slug());
        return streams.items(key.slug(), limit).stream()
            .map(item -> {
                String permalink = service.eventUrl(key.slug(), item.publicId());
                return new FeedItem(permalink,
                    item.url() == null ? permalink : item.url(),
                    item.title(),
                    item.summary(),
                    null,
                    item.summary(),
                    item.publishedAt(),
                    item.updatedAt(),
                    List.of(),
                    item.tags(),
                    List.of(),
                    null,
                    null,
                    null);
            })
            .toList();
    }

    @Override
    public FeedFingerprint fingerprint(FeedKey key, int limit) {
        require(key.slug());
        EventStreamRepository.FeedStats stats = streams.feedStats(key.slug(), limit);
        return new FeedFingerprint(stats.newestUpdated(), stats.rowCount(), stats.idDigest());
    }

    /** A stream nobody created is a 404 before any item is read, never an empty 200 feed. */
    private EventStreamRepository.Stream require(String slug) {
        return streams.find(slug).orElseThrow(() -> ApiException.notFound("unknown_event_stream",
            "No event stream " + slug));
    }
}
