package com.rsshub.events;

import com.rsshub.common.ApiException;
import com.rsshub.editorial.SiteIdentity;
import com.rsshub.feedrender.FeedFormat;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The permalink page for one ingested event (FR-1.3, FR-1.5).
 *
 * <p>An event's GUID is this URL, so it has to resolve to something a human can open: a guid that 404s is a broken
 * promise to every reader that stored it. Server-rendered for the same reason the posts are — a crawler that runs no
 * JavaScript still sees the discovery block.
 */
@Controller
public class EventViewController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    public record PublicEvent(String title, String url, String summary, List<String> tags, String publishedText,
        String streamTitle, String streamSlug) {
    }

    private final EventStreamRepository streams;
    private final SiteIdentity site;

    public EventViewController(EventStreamRepository streams, SiteIdentity site) {
        this.streams = streams;
        this.site = site;
    }

    @GetMapping("/event/{slug}/{publicId}")
    public String event(@PathVariable String slug, @PathVariable UUID publicId, Model model) {
        EventStreamRepository.Stream stream = streams.find(slug).orElseThrow(
            () -> ApiException.notFound("unknown_event_stream", "Nothing is published at /event/" + slug));
        EventStreamRepository.Item item = streams.findItemByPublicId(publicId)
            .filter(found -> found.streamId() == stream.id())
            .orElseThrow(() -> ApiException.notFound("unknown_event", "No event " + publicId + " in " + slug));

        model.addAttribute("site", site);
        model.addAttribute("title", item.title() + " · " + stream.title());
        model.addAttribute("event", new PublicEvent(item.title(), item.url(), item.summary(), item.tags(),
            day(item.publishedAt(), site.displayZone()), stream.title(), stream.slug()));
        model.addAttribute("feeds", alternates(new FeedKey(FeedScope.EVENTS, slug)));
        return "event";
    }

    /** One outlet — this stream — in all three formats, which is what a reader on this page can actually subscribe to. */
    private List<Map<String, String>> alternates(FeedKey key) {
        return Arrays.stream(FeedFormat.values())
            .map(format -> Map.of(
                "href", site.feedUrl(key, format),
                "type", format.mediaType().toString(),
                "title", streamLabel(key) + " (" + format.pathToken() + ")"))
            .toList();
    }

    private String streamLabel(FeedKey key) {
        return streams.find(key.slug())
            .map(EventStreamRepository.Stream::title)
            .orElseGet(site::siteTitle);
    }

    /** The stored instant in the site's display calendar; feeds always carry the real offset instead. */
    static String day(java.time.Instant instant, ZoneId zone) {
        return instant == null ? "" : DAY.format(instant.atZone(zone));
    }
}
