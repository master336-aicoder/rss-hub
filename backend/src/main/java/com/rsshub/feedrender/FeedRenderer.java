package com.rsshub.feedrender;

import com.rsshub.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Chooses the writer, supplies the channel fields a source should not have to know about (self URL, {@code ttl},
 * generator), and applies the two size rules. This is the only place that touches both a {@link FeedSource} and a
 * {@link FeedWriter}, which is what keeps the controller thin and the writers pure.
 *
 * <p>It deliberately does not reach into {@code feedstore} or {@code feedparse} (02 §3 rules 1 and 3): a public GET
 * gets its content through the SPI and nothing else.
 */
@Component
public class FeedRenderer {

    private final FeedSourceRegistry registry;
    private final Map<FeedFormat, FeedWriter> writers = new EnumMap<>(FeedFormat.class);
    private final PublishProperties props;

    public FeedRenderer(FeedSourceRegistry registry, List<FeedWriter> writerList, PublishProperties props) {
        this.registry = registry;
        this.props = props;
        for (FeedWriter writer : writerList) {
            writers.put(writer.format(), writer);
        }
    }

    public int itemLimit() {
        return props.itemLimit();
    }

    /** No source for the scope, or the scope's own lookup cannot find that slug: both are a 404 for an empty URL. */
    public FeedSource sourceFor(FeedKey key) {
        return registry.find(key).orElseThrow(() -> ApiException.notFound("unknown_outlet",
            "This site does not publish " + key.cacheKey()));
    }

    public FeedFingerprint fingerprint(FeedSource source, FeedKey key) {
        return source.fingerprint(key, props.itemLimit());
    }

    /**
     * The outlet's bytes, plus what the response line needs. {@code lastModified} comes from the fingerprint the
     * caller already paid for, so rendering never re-queries currency.
     */
    public RenderedFeed render(FeedSource source, FeedKey key, FeedFormat format, FeedFingerprint fingerprint) {
        FeedWriter writer = writers.get(format);
        if (writer == null) {
            throw ApiException.internal("no_writer", "No writer registered for " + format.lowercase() + " feeds");
        }
        FeedMeta meta = complete(source.meta(key), key, format, fingerprint);
        List<FeedItem> items = source.items(key, props.itemLimit());

        byte[] body = writer.render(meta, items);
        boolean degraded = false;
        // 03 §6.3 rule 7, and the only rule that can only be applied after serialising: measure, then drop bodies and
        // measure again. Bounded at three passes — newest keeps its body, then everyone but the newest, then nobody.
        if (format == FeedFormat.JSON && body.length > props.jsonMaxBytes()) {
            degraded = true;
            body = writer.render(meta, degrade(items));
            if (body.length > props.jsonMaxBytes()) {
                body = writer.render(meta, items.stream().map(FeedItem::withoutBody).toList());
            }
        }
        return new RenderedFeed(body, format.mediaType(), meta, items.size(), degraded,
            fingerprint.maxUpdatedAt());
    }

    /**
     * Summaries only. Newest keeps its body first and gives it up last: the item a subscriber is most likely to read
     * inline is the one worth the bytes. Repeated until the document fits, so the cap is a real bound rather than an
     * attempt — with everything but the first item already degraded, one more pass is the whole set degraded.
     */
    private static List<FeedItem> degrade(List<FeedItem> items) {
        List<FeedItem> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            out.add(i == 0 ? items.get(i) : items.get(i).withoutBody());
        }
        return out;
    }

    private FeedMeta complete(FeedMeta raw, FeedKey key, FeedFormat format, FeedFingerprint fingerprint) {
        String self = props.absolute(key.selfPath() + "." + format.pathToken());
        FeedMeta filled = raw.selfUrl() == null ? raw.withSelfUrl(self) : raw;
        Integer ttl = filled.ttlMinutes() == null ? props.ttlMinutes() : filled.ttlMinutes();
        String generator = filled.generator() == null ? props.generator() : filled.generator();
        Instant build = filled.lastBuildAt() != null ? filled.lastBuildAt() : fingerprint.maxUpdatedAt();
        return new FeedMeta(filled.title(), filled.siteUrl() == null ? props.baseUrl() : filled.siteUrl(),
            filled.description(), filled.language() == null ? props.language() : filled.language(), ttl,
            filled.imageUrl() == null ? props.imageUrl() : filled.imageUrl(), filled.selfUrl(), build, generator);
    }

    /**
     * @param degraded true when the JSON size rule dropped bodies, which the operator needs to see because it means
     *                 the outlet is carrying more content than one request should ship
     */
    public record RenderedFeed(byte[] body, MediaType contentType, FeedMeta meta,
        int itemCount, boolean degraded, Instant lastModified) {
    }
}
