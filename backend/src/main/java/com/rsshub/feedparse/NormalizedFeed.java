package com.rsshub.feedparse;

import java.util.List;

/**
 * 03 §5.1: the only model the ingestion side is allowed to see. It deliberately has no feed id, no row version and
 * no database types — {@code feedstore} maps it onto {@code feed}/{@code entry}, and adding a field here is a
 * deliberate widening of the contract between parsing and everything downstream.
 *
 * <p>02 §3 rule 1: this must never be reused as a render input. The publisher side has its own model
 * ({@code FeedSource}/{@code FeedItem}) precisely so an internal shape cannot leak into a public feed format.
 */
public record NormalizedFeed(String title, String siteUrl, String feedUrl, String description, String language,
    FeedFormat format, Integer ttlMinutes, List<NormalizedEntry> entries) {

    public NormalizedFeed {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
