package com.rsshub.adminapi;

import com.rsshub.events.EventIngestService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/ingest/events} (FR-3.1) — the one write endpoint a machine may call, authenticated by a
 * {@code ingest} scope token instead of the operator's session, and exempt from CSRF because it carries no cookie.
 *
 * <p>The stream is named in the body and defaults to {@code events}, so a single-token CI job can post without
 * knowing the URL layout; the path variant exists for the operator who runs several streams from one pipeline.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    /** The default stream slug, so a first-time caller does not have to create anything to test the link. */
    public static final String DEFAULT_STREAM = "events";

    public record IngestBody(@NotBlank @Size(max = 1024) String title, @Size(max = 2048) String url, String summary,
        List<String> tags, Instant publishedAt, @Size(max = 256) String dedupeKey, String stream) {
    }

    private final EventIngestService ingest;

    public IngestController(EventIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody @jakarta.validation.Valid IngestBody body) {
        String slug = body.stream() == null || body.stream().isBlank() ? DEFAULT_STREAM : body.stream().trim();
        return store(slug, body, true);
    }

    @PostMapping("/events/{slug}")
    public Map<String, Object> stream(@PathVariable String slug,
        @RequestBody @jakarta.validation.Valid IngestBody body) {
        return store(slug, body, false);
    }

    private Map<String, Object> store(String slug, IngestBody body, boolean createIfMissing) {
        EventIngestService.Stored stored = ingest.ingest(slug, new EventIngestService.IngestRequest(
            body.title(), body.url(), body.summary(), body.tags(), body.publishedAt(), body.dedupeKey()),
            createIfMissing);
        return Map.of("stream", stored.slug(), "id", stored.publicId().toString(), "created", stored.created());
    }
}
