package com.rsshub.feedrender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON Feed 1.1 (03 §6.3, column 3). Built through the configured {@link ObjectMapper} rather than by string
 * concatenation: the escaping rules for JSON differ from XML's, and a hand-rolled writer is how a quote in a title
 * becomes a broken feed.
 *
 * <p>{@code spring.jackson.default-property-inclusion: non_null} is what makes the "omit, never blank" rule work —
 * a null field is absent from the output, which is what JSON Feed asks for and what keeps the byte budget down.
 *
 * <p>No {@code hubs} member: WebSub is out of scope by decision (01 §2).
 */
@Component
public class JsonFeedWriter implements FeedWriter {

    /** The version URI is the format's own identifier; changing it is a new format, not a new release. */
    private static final String VERSION = "https://jsonfeed.org/version/1.1";

    private final ObjectMapper mapper;

    public JsonFeedWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public FeedFormat format() {
        return FeedFormat.JSON;
    }

    @Override
    public byte[] render(FeedMeta meta, List<FeedItem> items) {
        required("feed title", meta.title());
        List<Map<String, Object>> rendered = new ArrayList<>(items.size());
        for (FeedItem item : items) {
            rendered.add(item(item));
        }

        Map<String, Object> feed = new LinkedHashMap<>();
        put(feed, "version", VERSION);
        put(feed, "title", meta.title());
        put(feed, "home_page_url", meta.siteUrl());
        put(feed, "feed_url", meta.selfUrl());
        put(feed, "description", meta.description());
        put(feed, "icon", meta.imageUrl());
        put(feed, "language", meta.language());
        put(feed, "expired", false);
        put(feed, "generator", meta.generator());
        put(feed, "items", rendered);
        return write(feed);
    }

    private Map<String, Object> item(FeedItem item) {
        String id = required("item id", item.id());
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "id", id);
        put(out, "url", item.url() == null ? id : item.url());
        put(out, "external_url", item.sourceUrl());
        put(out, "title", item.title());
        // 1.1 wants content_text for plain bodies and content_html for markup; both are legal together, and this is
        // the only place in the three writers where the same body goes out twice on purpose.
        put(out, "content_text", item.contentText());
        put(out, "content_html", item.contentHtml());
        if (item.contentText() == null && item.contentHtml() == null && item.description() != null) {
            // A summary-only outlet still has to satisfy "either content_text or content_html" (rss-guide.md:327).
            out.put("content_text", item.description());
        }
        put(out, "image", item.imageUrl());
        put(out, "date_published", FeedDates.rfc3339(item.publishedAt()));
        put(out, "date_modified", FeedDates.rfc3339(item.updatedOrPublished()));
        if (!item.authors().isEmpty()) {
            out.put("authors", item.authors().stream().map(name -> Map.of("name", name)).toList());
        }
        if (!item.tags().isEmpty()) {
            out.put("tags", item.tags());
        }
        if (!item.enclosures().isEmpty()) {
            out.put("attachments", item.enclosures().stream().map(this::attachment).toList());
        }
        if (item.sourceUrl() != null) {
            // The spec has one field for "the original this was aggregated from", which F7 needs and nothing else
            // reads; rss_hub_source keeps the title alongside it, as a namespaced member per the spec's rule for
            // custom fields.
            out.put("rss_hub_source", Map.of("url", item.sourceUrl(),
                "title", item.sourceTitle() == null ? item.sourceUrl() : item.sourceTitle()));
        }
        return out;
    }

    /** {@code url} and {@code mime_type} are the two members the format marks required, so both are checked here. */
    private Map<String, Object> attachment(FeedItem.Enclosure enclosure) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "url", enclosure.url());
        put(out, "mime_type", required("attachment mime_type", enclosure.type()));
        put(out, "size_in_bytes", enclosure.length());
        return out;
    }

    /**
     * Nulls never enter the document. Spring's mapper is configured with {@code non_null} inclusion, but the writer
     * is used with plain mappers in unit tests too, so the rule belongs here rather than in a bean's config.
     */
    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private byte[] write(Map<String, Object> feed) {
        return mapper.writeValueAsBytes(feed);
    }
}
