package com.rsshub.editorial;

import com.rsshub.changelog.ChangeLogFeedSource;
import com.rsshub.common.ApiException;
import com.rsshub.editorial.PostRepository.PostRow;
import com.rsshub.feedrender.FeedFormat;
import com.rsshub.feedrender.FeedKey;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The public pages (03 §6.2: {@code /blog/{slug}} and the list). Server-rendered on purpose: a
 * {@code <link rel="alternate">} that only exists after a JavaScript bundle runs is invisible to every feed
 * aggregator and validator that matters (FR-1.3).
 *
 * <p>Reads only, and only rows the visibility rule already allows. Drafts are 404 rather than 403 — an editor's
 * private URL should not advertise that it exists.
 */
@Controller
public class PublicViewController {

    private final PostRepository posts;
    private final SiteIdentity site;

    public PublicViewController(PostRepository posts, SiteIdentity site) {
        this.posts = posts;
        this.site = site;
    }

    /**
     * A list row: enough to decide whether to open the post, never the body. Dates arrive pre-formatted because a
     * template that formats an {@code Instant} picks a zone by accident, and a blog that shows UTC to its author
     * looks a day out half the time.
     */
    public record PublicPost(String slug, String title, String summaryHtml, String publishedText, List<String> tags) {
    }

    @GetMapping({"/", "/blog"})
    public String list(Model model) {
        List<PublicPost> rows = posts.feedRows(null, null, 50).stream()
            .map(row -> new PublicPost(row.post().slug(), row.post().title(), summary(row.post()),
                day(row.post().publishedAt()), row.tags()))
            .toList();
        model.addAttribute("site", site);
        model.addAttribute("title", site.siteTitle());
        model.addAttribute("posts", rows);
        model.addAttribute("feeds", siteAlternates());
        return "list";
    }

    @GetMapping("/blog/{slug}")
    public String post(@PathVariable String slug, Model model) {
        PostRow post = posts.findBySlug(slug)
            .orElseThrow(() -> ApiException.notFound("unknown_post", "Nothing is published at /blog/" + slug));
        if (!isVisible(post)) {
            throw ApiException.notFound("unknown_post", "Nothing is published at /blog/" + slug);
        }
        List<String> tags = posts.tagSlugsOf(post.id());
        List<Map<String, String>> links = new ArrayList<>(siteAlternates());
        if (!tags.isEmpty()) {
            // The article's own granularity as well as the site's: a reader who subscribes from this page most
            // likely wants this tag, not everything the site has ever said.
            links.addAll(alternates(FeedKey.tag(tags.get(0))));
        }
        model.addAttribute("site", site);
        model.addAttribute("title", post.title());
        model.addAttribute("post", post);
        model.addAttribute("publishedText", day(post.publishedAt()));
        model.addAttribute("updatedText", day(post.updatedAt()));
        model.addAttribute("tags", tags);
        model.addAttribute("categories", posts.categorySlugsOf(post.id()));
        model.addAttribute("feeds", links);
        return "post";
    }

    private List<Map<String, String>> siteAlternates() {
        List<Map<String, String>> links = new ArrayList<>(alternates(FeedKey.site()));
        // FR-1.6: a changelog nobody can find is a changelog nobody subscribes to, so it is advertised next to the
        // site feed rather than only documented.
        links.addAll(alternates(FeedKey.changelog()));
        return links;
    }

    /**
     * The {@code rel=alternate} set 03 §6.2 promises: one entry per format, each carrying its own type so a client
     * can pick without guessing from the file extension.
     */
    private List<Map<String, String>> alternates(FeedKey key) {
        String label = labelFor(key);
        return Arrays.stream(FeedFormat.values())
            .map(format -> Map.of(
                "href", site.feedUrl(key, format),
                "type", format.mediaType().toString(),
                "title", label + " (" + format.pathToken() + ")"))
            .toList();
    }

    /**
     * A page that advertises the site feed, the changelog and the article's tag feed must not title every link after
     * the site: the label is the same one the outlet's own {@code <title>} uses, so the tag's display name rather than
     * its slug, with the slug as the fallback if the term row is gone.
     */
    private String labelFor(FeedKey key) {
        return switch (key.scope()) {
            case SITE -> site.siteTitle();
            // The outlet's own channel title, so the link and the feed it points at never disagree about the name.
            case CHANGELOG -> ChangeLogFeedSource.outletTitle(site.siteTitle());
            case TAG -> posts.findTag(key.slug()).map(PostRepository.Term::title).orElse(key.slug());
            case CATEGORY -> posts.findCategory(key.slug()).map(PostRepository.Term::title).orElse(key.slug());
            // M3..M5 scopes have no pages yet; naming them after the site is the only honest fallback.
            default -> site.siteTitle();
        };
    }

    /** Same rule the outlets use, stated once: published, or scheduled and now past its time. */
    private static boolean isVisible(PostRow post) {
        return "published".equals(post.status()) || "unlisted".equals(post.status())
            || ("scheduled".equals(post.status()) && post.publishedAt() != null
                && post.publishedAt().isBefore(Instant.now()));
    }

    private static String summary(PostRow post) {
        String configured = post.summaryMd();
        if (configured != null && !configured.isBlank()) {
            // A stored summary is Markdown the author wrote, so it needs the same render the body went through.
            return MarkdownRenderer.toHtml(configured);
        }
        return MarkdownRenderer.summarize(post.bodyHtml(), 240);
    }

    private String day(Instant instant) {
        return instant == null
            ? ""
            : DateTimeFormatter.ISO_LOCAL_DATE.withZone(site.displayZone()).format(instant);
    }
}
