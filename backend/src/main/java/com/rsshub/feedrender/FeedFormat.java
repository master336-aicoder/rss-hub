package com.rsshub.feedrender;

import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * The three output formats 03 §6.3 contracts. The media types are the de-facto values, not IANA registrations:
 * {@code application/rss+xml} and {@code application/feed+json} are unregistered (doc/rss-quickref.md:88), and
 * saying so in the output is a README point, not a code point.
 *
 * <p>Unrelated to {@code feedparse.FeedFormat}, which classifies what an upstream publisher sent us. ArchUnit keeps
 * the two from ever meeting (02 §3 rule 1).
 */
public enum FeedFormat {

    RSS("rss", MediaType.valueOf("application/rss+xml;charset=UTF-8")),
    ATOM("atom", MediaType.valueOf("application/atom+xml;charset=UTF-8")),
    JSON("json", MediaType.valueOf("application/feed+json;charset=UTF-8"));

    private final String pathToken;
    private final MediaType mediaType;

    FeedFormat(String pathToken, MediaType mediaType) {
        this.pathToken = pathToken;
        this.mediaType = mediaType;
    }

    public String pathToken() {
        return pathToken;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    /** A format the site does not publish is a 404, not a 400: the URL space is ours to define and this one is empty. */
    public static FeedFormat fromPath(String token) {
        for (FeedFormat candidate : values()) {
            if (candidate.pathToken.equalsIgnoreCase(token)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown feed format: " + token);
    }

    /** Case-stable so a lowercase constant can be dropped straight into a template's {@code .{format}} suffix. */
    public String lowercase() {
        return name().toLowerCase(Locale.ROOT);
    }
}
