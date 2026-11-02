package com.rsshub.adminapi;

import com.rsshub.events.EventStreamService;
import com.rsshub.events.EventStreamService.View;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stream administration (FR-3.3's switch included). This is the session-authenticated half; the token half is
 * {@link IngestController}, and the two only share the slug.
 */
@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    public record CreateRequest(@NotBlank @Size(max = 64) String slug, @NotBlank @Size(max = 512) String title,
        @Size(max = 2048) String description) {
    }

    public record UpdateRequest(@Size(max = 512) String title, @Size(max = 2048) String description) {
    }

    public record VisibilityRequest(@Pattern(regexp = "internal|virtual_feed") String visibility) {
    }

    private final EventStreamService streams;

    public EventStreamController(EventStreamService streams) {
        this.streams = streams;
    }

    @GetMapping
    public List<View> list() {
        return streams.list();
    }

    @GetMapping("/{slug}")
    public View get(@PathVariable String slug) {
        return streams.get(slug);
    }

    @PostMapping
    public View create(@RequestBody @jakarta.validation.Valid CreateRequest request) {
        return streams.create(request.slug(), request.title(), request.description());
    }

    @PatchMapping("/{slug}")
    public View update(@PathVariable String slug, @RequestBody @jakarta.validation.Valid UpdateRequest request) {
        return streams.update(slug, request.title(), request.description());
    }

    @PatchMapping("/{slug}/visibility")
    public View visibility(@PathVariable String slug,
        @RequestBody @jakarta.validation.Valid VisibilityRequest request) {
        return streams.setVisibility(slug, request.visibility());
    }

    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        streams.delete(slug);
    }
}
