package com.rsshub.feedparse;

import java.util.Locale;

/** 03 §5.3: which rung of the id ladder produced {@code NormalizedEntry.guid}. Persisted in {@code entry.meta}. */
public enum GuidSource {
    /** RSS {@code <guid>}. */
    GUID,
    /** Atom {@code <id>}. */
    ATOM_ID,
    /** JSON Feed {@code items[].id} */
    JSON_ID,
    /** The entry's own link, absolutised. */
    LINK,
    /** Nothing above existed: a hash of the stable-looking parts. The health page flags these sources. */
    FALLBACK_HASH;

    /**
     * Lower-case, because this string is stored twice per row — as the {@code fallback_hash:} prefix of a generated
     * guid and as {@code meta.guid_source} — and the health page groups on it. {@link #stored()} matches
     * {@code FeedFormat.stored()} so the two provenance columns read the same way.
     */
    public String stored() {
        return name().toLowerCase(Locale.ROOT);
    }
}
