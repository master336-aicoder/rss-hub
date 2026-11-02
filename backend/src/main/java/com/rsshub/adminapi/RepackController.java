package com.rsshub.adminapi;

import com.rsshub.repack.RepackService;
import com.rsshub.repack.RepackService.AuditView;
import com.rsshub.repack.RepackService.Detail;
import com.rsshub.repack.RepackService.Summary;
import jakarta.validation.constraints.NotNull;
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
 * Forwarding collections (FR-7.1) and their per-site full-content switch (FR-7.2).
 *
 * <p>Every member write returns the whole collection rather than a bare ack: the screen that flips a switch also
 * shows the entry counts and the flag's date, and asking the browser to re-GET after each PATCH is a second chance
 * for the two halves to disagree.
 */
@RestController
@RequestMapping("/api/repacks")
public class RepackController {

    public record CreateRequest(@jakarta.validation.constraints.NotBlank @Size(max = 64) String slug,
        @jakarta.validation.constraints.NotBlank @Size(max = 512) String title, @Size(max = 2048) String description) {
    }

    public record UpdateRequest(@Size(max = 512) String title, @Size(max = 2048) String description) {
    }

    public record AddSourceRequest(@NotNull Long feedId) {
    }

    public record FullContentRequest(@NotNull Boolean fullContent) {
    }

    private final RepackService repacks;

    public RepackController(RepackService repacks) {
        this.repacks = repacks;
    }

    @GetMapping
    public List<Summary> list() {
        return repacks.list();
    }

    @GetMapping("/{slug}")
    public Detail get(@PathVariable String slug) {
        return repacks.get(slug);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@RequestBody @jakarta.validation.Valid CreateRequest request) {
        return repacks.create(request.slug(), request.title(), request.description());
    }

    @PatchMapping("/{slug}")
    public Detail update(@PathVariable String slug, @RequestBody @jakarta.validation.Valid UpdateRequest request) {
        return repacks.update(slug, request.title(), request.description());
    }

    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        repacks.delete(slug);
    }

    /** The copyright trail 04 §M5 asks for: which member had full text, and when it changed. */
    @GetMapping("/{slug}/audit")
    public List<AuditView> audit(@PathVariable String slug) {
        return repacks.audit(slug);
    }

    @PostMapping("/{slug}/feeds")
    public Detail addSource(@PathVariable String slug, @RequestBody @jakarta.validation.Valid AddSourceRequest request) {
        return repacks.addFeed(slug, request.feedId());
    }

    @PatchMapping("/{slug}/feeds/{feedId}")
    public Detail setFullContent(@PathVariable String slug, @PathVariable long feedId,
        @RequestBody @jakarta.validation.Valid FullContentRequest request) {
        return repacks.setFullContent(slug, feedId, request.fullContent());
    }

    @DeleteMapping("/{slug}/feeds/{feedId}")
    public Detail removeSource(@PathVariable String slug, @PathVariable long feedId) {
        return repacks.removeFeed(slug, feedId);
    }
}
