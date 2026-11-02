package com.rsshub.feedrender;

import java.time.Instant;
import java.util.List;

/**
 * One entry in a published outlet — the render-side model, which 02 §3 rule 1 keeps separate from
 * {@code feedparse.NormalizedEntry}. A publisher's job is to describe what it wrote; a subscriber's job is to
 * survive what someone else wrote, and sharing the type would push that tolerance into the public output.
 *
 * @param id          permanent, absolute, never rewritten (03 §6.3, doc/rss-quickref.md:133): the post's own URL
 * @param url         the human page, which is usually the same as {@code id} but not for a republished item
 * @param description short HTML for {@code description}/{@code summary}/{@code content_text}
 * @param contentHtml full sanitized body; null means "this outlet only carries the summary", which is a legal feed
 * @param publishedAt RSS {@code pubDate}, Atom {@code published}, JSON {@code date_published}
 * @param updatedAt   Atom's mandatory {@code updated}; required by JSON Feed's validation and by 03 §6.3
 */
public record FeedItem(String id, String url, String title, String description, String contentHtml,
    String contentText, Instant publishedAt, Instant updatedAt, List<String> authors, List<String> tags,
    List<Enclosure> enclosures, String imageUrl, String sourceUrl, String sourceTitle) {

    public FeedItem {
        authors = authors == null ? List.of() : List.copyOf(authors);
        tags = tags == null ? List.of() : List.copyOf(tags);
        enclosures = enclosures == null ? List.of() : List.copyOf(enclosures);
    }

    /**
     * A media attachment. {@code length} is the byte size of the file at {@code url} as it will be served, which is
     * why it may never be the size of a gzip'd response (doc/rss-guide.md:507) — M4 owns that rule, M2 only has
     * images here.
     */
    public record Enclosure(String url, String type, Long length) {
    }

    /** The timestamp every format needs: {@code updatedAt} is mandatory in Atom, so fall back to publication. */
    public Instant updatedOrPublished() {
        return updatedAt != null ? updatedAt : publishedAt;
    }

    /** Degraded copy used when a JSON Feed blows the 100KB budget (03 §6.3 rule 7): body out, link stays. */
    public FeedItem withoutBody() {
        return new FeedItem(id, url, title, description, null, null, publishedAt, updatedAt, authors, tags,
            enclosures, imageUrl, sourceUrl, sourceTitle);
    }
}
