package com.rsshub.feedparse;

import java.util.Locale;

/** The four shapes 03 §5.2 sniffs. {@code stored()} is also what the health page and {@code feed.meta} show. */
public enum FeedFormat {
    RSS2,
    ATOM,
    RDF,
    JSON;

    /** Locale.ROOT, because a Turkish default locale would turn a plain lower-casing into a different string. */
    public String stored() {
        return name().toLowerCase(Locale.ROOT);
    }
}
