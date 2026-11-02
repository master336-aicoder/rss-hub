package com.rsshub.feedparse;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One normalised entry (03 §5.1). Everything in here is either verbatim from the document or the result of a
 * documented normalisation rule — the id ladder in §5.3, the date ladder in {@link FlexibleDates}, the body
 * priority and whitelist in §5.5. It carries no feed id and no row identity, so a parse of the same bytes is
 * always comparable to itself, which is what {@code feedstore}'s content-hash upsert relies on.
 */
public record NormalizedEntry(String guid, GuidSource guidSource, String url, String title, String summary,
    String contentHtml, List<Author> authors, List<String> categories, List<Enclosure> enclosures,
    Instant publishedAt, Instant updatedAt, String lang, Map<String, String> extras) {

    /** {@code name} is what a reader shows; email and uri are kept because Atom and RSS express people differently. */
    public record Author(String name, String email, String uri) {
    }

    /**
     * Audio attached to an entry. {@code durationSeconds} comes from {@code <itunes:duration>} or JSON Feed's
     * {@code duration_in_seconds}; it is a hint, not a promise, and the podcast module re-measures the real file.
     */
    public record Enclosure(String url, String mimeType, Long sizeBytes, Long durationSeconds) {
    }

    public NormalizedEntry {
        authors = authors == null ? List.of() : List.copyOf(authors);
        categories = categories == null ? List.of() : List.copyOf(categories);
        enclosures = enclosures == null ? List.of() : List.copyOf(enclosures);
        // Insertion order kept: the health page shows extras as they were found, and Map.copyOf would shuffle them
        // differently on every run, which makes a diff of two fetches meaningless.
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** 03 §5.3 rule 5: only a hash-backed id is unstable — a title edit makes it a "new" article. */
    public boolean idIsStable() {
        return guidSource != GuidSource.FALLBACK_HASH;
    }
}
