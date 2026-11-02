package com.rsshub.editorial;

import com.rsshub.changelog.ChangeLogRepository;
import com.rsshub.changelog.ChangeLogRepository.ChangeDraft;
import com.rsshub.common.ApiException;
import com.rsshub.editorial.PostRepository.PostRow;
import com.rsshub.editorial.PostRepository.Term;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The post lifecycle (FR-1.1): normalise the input, render the body, own the status machine, keep the slug frozen.
 *
 * <p>The render happens here rather than in a getter so that "what is stored is what is published" stays true — the
 * feed writer, the public page and the editor all read the same {@code body_html} column, which is the only way
 * 03 §5.5's argument ("the same content must not look different in two places") survives three rendering paths.
 *
 * <p>Every write also offers the change to {@link ChangeLogRepository} (FR-1.6), which is what lets the changelog
 * outlet say what happened without the render layer having to diff the post table against itself.
 */
@Service
public class PostService {

    /** The DDL's own CHECK list (03 §3.6), repeated so a typo in a request is a 400 and not a constraint error. */
    private static final Set<String> STATUSES = Set.of("draft", "scheduled", "published", "unlisted", "archived");

    /**
     * Which state may become which. Unlisted is reachable only from published, because "it went out and now it is off
     * the lists" is the only thing that produces it. Coming back out of archive is an explicit human decision, so
     * both draft and published are allowed targets.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "draft", Set.of("scheduled", "published", "archived"),
        "scheduled", Set.of("draft", "published", "archived"),
        "published", Set.of("draft", "unlisted", "archived"),
        "unlisted", Set.of("published", "archived"),
        "archived", Set.of("draft", "published"));

    private final PostRepository posts;
    private final SiteIdentity site;
    private final ChangeLogRepository changes;

    public PostService(PostRepository posts, SiteIdentity site, ChangeLogRepository changes) {
        this.posts = posts;
        this.site = site;
        this.changes = changes;
    }

    /**
     * @param slug null derives it from the title; never read again after creation, whatever a later request sends
     * @param tags free-text names, resolved or created in the order given
     */
    public record PostRequest(String title, String slug, String summaryMd, String bodyMd, String status, String lang,
        Instant publishedAt, List<String> tags, List<String> categories) {
    }

    /** What the editor and the public page show. {@code url} is computed, never stored. */
    public record PostView(long id, String slug, String title, String summaryMd, String bodyMd, String bodyHtml,
        String status, String lang, Instant publishedAt, Instant updatedAt, String url, List<String> tags,
        List<String> categories) {
    }

    public PostView create(PostRequest request) {
        String title = require(request.title(), "title");
        String bodyMd = require(request.bodyMd(), "body_md");
        String status = orDefault(request.status(), "draft");
        requireStatus(status);
        Instant publishedAt = stamp(status, request.publishedAt(), null);

        String slug = chooseSlug(request.slug(), title);
        if (posts.findBySlug(slug).isPresent()) {
            throw ApiException.conflict("slug_taken", "A post already lives at /blog/" + slug);
        }

        long id = posts.create(slug, title, blankToNull(request.summaryMd()), bodyMd,
            MarkdownRenderer.toHtml(bodyMd), status, request.lang(), publishedAt, null);
        applyTerms(id, request);
        offer(id, slug, title, ChangeLogRepository.CREATED, null, status, publishedAt);
        return view(posts.require(id));
    }

    /**
     * Content edit. The request's slug is ignored by design: {@code PostRepository} has no statement that sets that
     * column after INSERT, so a stale editor tab cannot rewrite a permanent URL.
     */
    public PostView update(long id, PostRequest request) {
        PostRow existing = posts.require(id);
        String title = require(request.title(), "title");
        String bodyMd = request.bodyMd() == null ? existing.bodyMd() : require(request.bodyMd(), "body_md");
        Instant requested = request.publishedAt();
        if ("scheduled".equals(existing.status()) && requested == null) {
            throw ApiException.badRequest("publish_at_required", "A scheduled post needs its publish time");
        }
        // An editor that PUTs the document it just read is not a change, and the changelog is only interesting if it
        // can say that. The four columns updateContent writes are the four compared here; tags are deliberately not
        // one of them — re-tagging a post is not news to a subscriber.
        boolean changed = !title.equals(existing.title())
            || !bodyMd.equals(existing.bodyMd())
            || !Objects.equals(blankToNull(request.summaryMd()), existing.summaryMd())
            || !Objects.equals(request.lang(), existing.lang())
            || (requested != null && !requested.equals(existing.publishedAt()));
        Instant publishedAt = requested == null ? existing.publishedAt() : requested;

        posts.updateContent(id, title, blankToNull(request.summaryMd()), bodyMd, MarkdownRenderer.toHtml(bodyMd),
            request.lang(), publishedAt);
        applyTerms(id, request);
        if (changed) {
            offer(id, existing.slug(), title, ChangeLogRepository.UPDATED, existing.status(), existing.status(),
                publishedAt);
        }
        return view(posts.require(id));
    }

    public PostView changeStatus(long id, String target, Instant publishedAt) {
        PostRow existing = posts.require(id);
        requireStatus(target);
        if (existing.status().equals(target)) {
            return view(existing);
        }
        if (!TRANSITIONS.get(existing.status()).contains(target)) {
            throw ApiException.conflict("illegal_transition", existing.status() + " cannot become " + target);
        }
        Instant stamped = stamp(target, publishedAt, existing.publishedAt());
        posts.updateStatus(id, target, stamped);
        offer(id, existing.slug(), existing.title(), ChangeLogRepository.STATUS, existing.status(), target, stamped);
        return view(posts.require(id));
    }

    /** The last status is captured before the row goes, because a retraction has to name what it retracted. */
    public void delete(long id) {
        PostRow existing = posts.require(id);
        posts.delete(id);
        offer(id, existing.slug(), existing.title(), ChangeLogRepository.DELETED, existing.status(), null,
            existing.publishedAt());
    }

    public List<PostView> list(String status) {
        return posts.listAll(status).stream().map(summary -> view(posts.require(summary.id()))).toList();
    }

    public PostView get(long id) {
        return view(posts.require(id));
    }

    public PostView bySlug(String slug) {
        return posts.findBySlug(slug).map(this::view)
            .orElseThrow(() -> ApiException.notFound("unknown_post", "No post at /blog/" + slug));
    }

    public List<Term> tags() {
        return posts.listTags();
    }

    public List<Term> categories() {
        return posts.listCategories();
    }

    /**
     * Publication time is stamped once and then kept. Publishing without a date takes "now"; a post going back to
     * draft keeps its date, so republishing does not move {@code atom:published} and make every reader treat it as
     * new. Only {@code scheduled} insists on being told.
     */
    private static Instant stamp(String status, Instant requested, Instant existing) {
        Instant settled = requested != null ? requested : existing;
        return switch (status) {
            case "scheduled" -> {
                if (settled == null) {
                    throw ApiException.badRequest("publish_at_required",
                        "A scheduled post needs a publish time before it can leave the draft list");
                }
                yield settled;
            }
            case "published", "unlisted" -> settled == null ? Instant.now() : settled;
            default -> settled;
        };
    }

    /**
     * Hands one write to the changelog. The URL is the snapshot the outlet publishes, so it is built here, where the
     * permalink rule lives, rather than by the render layer (02 §3 rule 1: a source does not get to decide what an
     * article's permanent address is).
     */
    private void offer(long postId, String slug, String title, String action, String fromStatus, String toStatus,
        Instant publishedAt) {
        changes.recordIfPublic(new ChangeDraft(postId, slug, title, site.postUrl(slug), action, fromStatus, toStatus,
            publishedAt), Instant.now());
    }

    private void applyTerms(long id, PostRequest request) {
        posts.replaceTags(id, resolveTerms(request.tags(), true));
        posts.replaceCategories(id, resolveTerms(request.categories(), false));
    }

    private List<Long> resolveTerms(List<String> names, boolean tag) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                ids.add((tag ? posts.resolveTag(name.strip()) : posts.resolveCategory(name.strip())).id());
            }
        }
        return ids.stream().distinct().toList();
    }

    private PostView view(PostRow post) {
        return new PostView(post.id(), post.slug(), post.title(), post.summaryMd(), post.bodyMd(), post.bodyHtml(),
            post.status(), post.lang(), post.publishedAt(), post.updatedAt(), site.postUrl(post.slug()),
            posts.tagSlugsOf(post.id()), posts.categorySlugsOf(post.id()));
    }

    /**
     * An absent slug is derived from the title; an illegal one is refused rather than quietly rewritten, because the
     * author is about to publish a URL they cannot change later and deserves to hear "that one will not work" now.
     */
    private static String chooseSlug(String requested, String title) {
        if (requested == null || requested.isBlank()) {
            return SlugPolicy.derive(null, title);
        }
        if (!SlugPolicy.isLegal(requested)) {
            throw ApiException.badRequest("invalid_slug",
                "A slug may not contain spaces, slashes, or anything a URL would have to escape");
        }
        return requested;
    }

    private static void requireStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw ApiException.badRequest("invalid_status", "Status must be one of " + String.join(", ", STATUSES));
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_field", field + " is required");
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
