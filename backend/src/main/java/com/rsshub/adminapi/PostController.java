package com.rsshub.adminapi;

import com.rsshub.editorial.PostRepository.Term;
import com.rsshub.editorial.PostService;
import com.rsshub.editorial.PostService.PostRequest;
import com.rsshub.editorial.PostService.PostView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's API (FR-1.1). The list returns whole posts including {@code body_html}, which is fine at self-hosted
 * volumes and is what lets the editor show a preview without a second request per row.
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    /** A status change is its own verb-level operation, so it gets its own tiny body instead of a full PUT. */
    public record StatusRequest(@NotBlank String status, Instant publishedAt) {
    }

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @GetMapping
    public List<PostView> list(@RequestParam(required = false) String status) {
        return posts.list(status);
    }

    @GetMapping("/{id}")
    public PostView get(@PathVariable long id) {
        return posts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PostView create(@Valid @RequestBody CreateRequest request) {
        return posts.create(request.toCommand());
    }

    @PutMapping("/{id}")
    public PostView update(@PathVariable long id, @Valid @RequestBody CreateRequest request) {
        return posts.update(id, request.toCommand());
    }

    @PatchMapping("/{id}/status")
    public PostView status(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return posts.changeStatus(id, request.status(), request.publishedAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        posts.delete(id);
    }

    /** The two term lists the editor's pickers need, in the shape the SPA already uses for feed groups. */
    @GetMapping("/terms")
    public Map<String, List<Term>> terms() {
        return Map.of("tags", posts.tags(), "categories", posts.categories());
    }

    /**
     * @param slug optional; blank derives it from the title and the derivation is then permanent
     */
    public record CreateRequest(@NotBlank String title, String slug, String summaryMd, @NotBlank String bodyMd,
        String status, String lang, Instant publishedAt, List<String> tags, List<String> categories) {

        PostRequest toCommand() {
            return new PostRequest(title, slug, summaryMd, bodyMd, status, lang, publishedAt, tags, categories);
        }
    }
}
