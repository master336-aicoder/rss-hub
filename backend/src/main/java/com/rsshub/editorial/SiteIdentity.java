package com.rsshub.editorial;

import com.rsshub.feedrender.FeedFormat;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import com.rsshub.feedrender.PublishProperties;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Everything a public page or outlet needs to build a URL: the site origin, the permalink rule, and the three feed
 * serialisations of one slice. Lives on the editorial side because the URL space is a publishing decision, and
 * {@code feedrender} is not allowed to know what a post is (02 §3 rule 1).
 */
@Component
public class SiteIdentity {

    private final PublishProperties props;

    public SiteIdentity(PublishProperties props) {
        this.props = props;
    }

    /** The permanent URL of a post: its guid, its {@code atom:id} and its JSON Feed {@code id} (FR-1.5). */
    public String postUrl(String slug) {
        return props.absolute("/blog/" + slug);
    }

    /** Absolute outlet URL for one slice in one format: {@code https://example.org/tag/observability.atom}. */
    public String feedUrl(FeedScope scope, String slug, FeedFormat format) {
        return props.absolute(scope.pathFor(slug) + "." + format.pathToken());
    }

    public String feedUrl(FeedKey key, FeedFormat format) {
        return props.absolute(key.selfPath() + "." + format.pathToken());
    }

    public String baseUrl() {
        return props.baseUrl();
    }

    public String siteTitle() {
        return props.siteTitle();
    }

    public String siteDescription() {
        return props.description();
    }

    public String language() {
        return props.language();
    }

    public String imageUrl() {
        return props.imageUrl();
    }

    /** The calendar the public pages print a date in. Feeds never use it — they carry the real offset. */
    public ZoneId displayZone() {
        return ZoneId.of(props.displayZone());
    }

    public String absolute(String path) {
        return props.absolute(path);
    }
}
