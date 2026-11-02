package com.rsshub.feedrender;

import com.rsshub.common.ApiException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public outlets (FR-1.2, FR-1.4). Anonymous, read-only, no session, and the only paths in the app that are not
 * under {@code /api}.
 *
 * <p>The 304 short-circuit runs before rendering: an outlet's {@link FeedFingerprint} costs one aggregate query, so a
 * poller that already has the current copy is answered without touching a single body column. That is what makes
 * "everyone I follow asks me for their feed every minute" a cheap thing to run.
 */
@RestController
public class PublicFeedController {

    private static final Logger log = LoggerFactory.getLogger(PublicFeedController.class);

    /**
     * One minute, private, must-revalidate. Long enough that a reader polling several outlets from one host is not
     * re-fetching all of them within the same second, short enough that a new post is visible on the next poll
     * (03 §6.4). {@code cachePrivate} matters because the operator's own inbox is authenticated and a shared cache
     * must not serve it to the next visitor.
     */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate().mustRevalidate();

    private final FeedRenderer renderer;
    private final EtagService etags;

    public PublicFeedController(FeedRenderer renderer, EtagService etags) {
        this.renderer = renderer;
        this.etags = etags;
    }

    @GetMapping("/feed.{format}")
    public ResponseEntity<byte[]> site(@PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(FeedKey.site(), format, ifNoneMatch);
    }

    /**
     * The site's change log (FR-1.6). The mapping is a literal, and Spring scores an exact segment above a captured
     * one, so {@code /feed/changelog.rss} is this outlet and not an event stream — even on a site that also runs a
     * stream named {@code changelog}, which then has no RSS address. That is the cost of the shorter URL and 03 §6.2
     * records it.
     */
    @GetMapping("/feed/changelog.{format}")
    public ResponseEntity<byte[]> changelog(@PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(FeedKey.changelog(), format, ifNoneMatch);
    }

    @GetMapping("/tag/{slug}.{format}")
    public ResponseEntity<byte[]> tag(@PathVariable String slug, @PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(FeedKey.tag(slug), format, ifNoneMatch);
    }

    @GetMapping("/category/{slug}.{format}")
    public ResponseEntity<byte[]> category(@PathVariable String slug, @PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(FeedKey.category(slug), format, ifNoneMatch);
    }

    /**
     * An event stream's outlet (FR-3.4). M3 added a bean and this one mapping — no writer, renderer or ETag code
     * changed, which is the FeedSource SPI doing the job 03 §6.1 designed it for.
     */
    @GetMapping("/feed/{slug}.{format}")
    public ResponseEntity<byte[]> eventStream(@PathVariable String slug, @PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(new FeedKey(FeedScope.EVENTS, slug), format, ifNoneMatch);
    }

    /**
     * A forwarding collection's outlet (FR-7.1). One mapping and one bean, same as M3's event outlet: the writers
     * already emit {@code <source>}, {@code atom:source} and {@code external_url}, so the render layer did not change.
     */
    @GetMapping("/repack/{slug}.{format}")
    public ResponseEntity<byte[]> repack(@PathVariable String slug, @PathVariable String format,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return outlet(new FeedKey(FeedScope.REPACK, slug), format, ifNoneMatch);
    }

    private ResponseEntity<byte[]> outlet(FeedKey key, String formatToken, String ifNoneMatch) {
        FeedFormat format = parseFormat(formatToken);
        FeedSource source = renderer.sourceFor(key);
        FeedFingerprint fingerprint = renderer.fingerprint(source, key);
        String etag = etags.forOutlet(key, format, fingerprint);

        if (etags.matches(ifNoneMatch, etag)) {
            // 304 carries the validators and nothing else, so an unchanged outlet costs no serialisation at all.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CACHE).build();
        }

        FeedRenderer.RenderedFeed rendered = renderer.render(source, key, format, fingerprint);
        if (rendered.degraded()) {
            log.warn("outlet {} exceeds the JSON Feed size budget; {} summaries shipped without bodies",
                key.cacheKey(), rendered.itemCount());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CACHE)
            .contentType(rendered.contentType());
        if (rendered.lastModified() != null) {
            response.lastModified(rendered.lastModified());
        }
        return response.body(rendered.body());
    }

    /** An unknown suffix is a URL this site does not have, so it is a 404 and not a 400 about the caller's syntax. */
    private static FeedFormat parseFormat(String token) {
        try {
            return FeedFormat.fromPath(token);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("unknown_format", "This site publishes rss, atom and json outlets");
        }
    }
}
