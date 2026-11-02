package com.rsshub.feedrender;

import com.rsshub.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns a channel plus its items into the bytes of one format (03 §6.3). Writers are pure: no database, no clock
 * outside what the meta and items already carry, no HTTP. That purity is what makes the golden-file test possible
 * and what keeps 02 §3 rule 3 ("a GET must not write") true by construction rather than by review.
 */
public interface FeedWriter {

    FeedFormat format();

    /**
     * The document bytes, UTF-8 with no BOM. The XML writers start with the declaration at byte zero
     * (03 §6.3 rule 1, doc/rss-guide.md:610) and the JSON writer starts at "{", so returning bytes rather than a
     * String keeps the charset decision inside the writer.
     */
    byte[] render(FeedMeta meta, List<FeedItem> items);

    /** The XML declaration, exactly once, as the first bytes of the response. */
    String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    /**
     * A required element with text content. An outlet that cannot supply one is broken and must fail rather than
     * publish an empty element: a channel with {@code <description/>} passes the DTD's "element exists" check and
     * still gets rejected by validators, which is the worst of both outcomes.
     */
    default String required(String label, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.internal("feed_incomplete",
                "The " + format().lowercase() + " feed is missing its required " + label);
        }
        return value;
    }

    /** Serialise to UTF-8 without a BOM; the byte order mark is what makes a valid XML declaration invalid. */
    static byte[] utf8(String document) {
        return document.getBytes(StandardCharsets.UTF_8);
    }

    /** An item that cannot be named is a source bug, and the id is what the operator needs to find it. */
    static ApiException incomplete(FeedFormat format, String label, String itemId) {
        return ApiException.internal("feed_incomplete",
            "The " + format.lowercase() + " feed is missing a required " + label + " for item " + itemId);
    }

    /**
     * Whether a guid can honestly claim {@code isPermaLink="true"} (doc/rss-quickref.md:133 recommends the permanent
     * URL form). Anything that is not an absolute http(s) URL has to be published as an opaque id instead.
     */
    static boolean isUrl(String candidate) {
        return candidate != null
            && (candidate.startsWith("http://") || candidate.startsWith("https://"))
            && candidate.length() > "https://".length();
    }
}
