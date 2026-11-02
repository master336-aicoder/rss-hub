package com.rsshub.feedrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The renderer's own two responsibilities, away from a database: filling in the channel fields a source must not have
 * to know about, and applying 03 §6.3 rule 7 — the only output rule that can be measured after serialising rather
 * than before it.
 */
class FeedRendererTest {

    private static final PublishProperties PROPS =
        new PublishProperties("https://notes.example.org", "Notes", "A site", "zh-CN", null, 50, 60, 2_000,
            "rss-hub", "Asia/Shanghai");

    /** A source that answers one fixed slice, so the only thing under test is the renderer. */
    private static final class Stub implements FeedSource {

        private List<FeedItem> items = List.of();
        private int seenLimit;

        @Override
        public FeedScope scope() {
            return FeedScope.SITE;
        }

        @Override
        public FeedMeta meta(FeedKey key) {
            return new FeedMeta("Notes", "https://notes.example.org/", "A site", null, null, null, null, null, null);
        }

        @Override
        public List<FeedItem> items(FeedKey key, int limit) {
            this.seenLimit = limit;
            return items.stream().limit(limit).toList();
        }

        @Override
        public FeedFingerprint fingerprint(FeedKey key, int limit) {
            return items.isEmpty() ? FeedFingerprint.empty()
                : new FeedFingerprint(Instant.parse("2026-10-18T00:00:00Z"), Math.min(items.size(), limit), "digest");
        }
    }

    private final Stub stub = new Stub();
    private final FeedRenderer renderer = new FeedRenderer(new FeedSourceRegistry(List.of(stub)),
        List.of(new Rss2Writer(), new AtomWriter(), new JsonFeedWriter(new ObjectMapper())), PROPS);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void emptySource() {
        stub.items = List.of();
    }

    @Test
    void theChannelFieldsASourceLeavesBlankComeFromConfig() throws Exception {
        stub.items = List.of(item("https://notes.example.org/blog/a", "A", "body", "summary"));
        FeedRenderer.RenderedFeed rendered = render(FeedFormat.RSS);
        String xml = new String(rendered.body(), StandardCharsets.UTF_8);

        assertThat(xml).contains("<atom:link href=\"https://notes.example.org/feed.rss\" rel=\"self\"")
            .contains("<ttl>60</ttl>")
            .contains("<language>zh-CN</language>")
            .contains("<generator>rss-hub</generator>");
        assertThat(rendered.meta().selfUrl()).isEqualTo("https://notes.example.org/feed.rss");
    }

    @Test
    @DisplayName("rule 5: the budget is handed to the source, so the query caps rather than the writer trimming")
    void theItemLimitReachesTheSource() {
        List<FeedItem> many = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            many.add(item("https://notes.example.org/blog/" + i, "Post " + i, "b", "s"));
        }
        stub.items = many;

        FeedRenderer.RenderedFeed rendered = render(FeedFormat.RSS);
        // The budget is handed to the source, so the query caps it: Post 50 is in the document and Post 51 was never
        // fetched. Trimming in the writer would still pay for all one hundred rows.
        assertThat(stub.seenLimit).isEqualTo(50);
        assertThat(rendered.itemCount()).isEqualTo(50);
        String xml = new String(rendered.body(), StandardCharsets.UTF_8);
        assertThat(countOf(xml, "<item>")).isEqualTo(50);
        assertThat(xml).contains("<title>Post 50</title>").doesNotContain("<title>Post 51</title>");
    }

    @Test
    @DisplayName("rule 7: over the byte budget the bodies go, the items do not")
    void anOverlongJsonFeedDropsBodiesAndKeepsEveryItem() throws Exception {
        stub.items = List.of(
            item("https://notes.example.org/blog/1", "One", "x".repeat(900), "summary one"),
            item("https://notes.example.org/blog/2", "Two", "y".repeat(900), "summary two"),
            item("https://notes.example.org/blog/3", "Three", "z".repeat(900), "summary three"));

        FeedRenderer.RenderedFeed rendered = render(FeedFormat.JSON);
        assertThat(rendered.degraded()).isTrue();
        assertThat(rendered.body().length).isLessThanOrEqualTo(2_000);

        JsonNode items = mapper.readTree(rendered.body()).get("items");
        assertThat(items.size()).isEqualTo(3);
        // The newest item keeps its body and gives it up last; the rest ship summary plus link.
        assertThat(items.get(0).has("content_html")).isTrue();
        assertThat(items.get(1).has("content_html")).isFalse();
        assertThat(items.get(2).has("content_html")).isFalse();
        for (JsonNode item : items) {
            assertThat(item.get("id").asString()).isNotBlank();
            assertThat(item.get("url").asString()).isNotBlank();
        }
    }

    @Test
    void aDocumentAlreadyInsideTheBudgetIsNotTouched() throws Exception {
        stub.items = List.of(item("https://notes.example.org/blog/1", "One", "short body", "summary one"));
        FeedRenderer.RenderedFeed rendered = render(FeedFormat.JSON);
        assertThat(rendered.degraded()).isFalse();
        assertThat(mapper.readTree(rendered.body()).get("items").get(0).get("content_html").asString())
            .isEqualTo("short body");
    }

    @Test
    void anOutletWithNoSourceIsAFourOhFourNotAnEmptyTwoHundred() {
        FeedRenderer bare = new FeedRenderer(new FeedSourceRegistry(List.of()), List.of(new Rss2Writer()), PROPS);
        assertThatThrownBy(() -> bare.sourceFor(new FeedKey(FeedScope.SHOW, "season-one")))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status().is4xxClientError()).isTrue();
                assertThat(e.code()).isEqualTo("unknown_outlet");
            });
    }

    @Test
    void theSameScopeCannotBeClaimedByTwoSources() {
        assertThatThrownBy(() -> new FeedSourceRegistry(List.of(stub, stub)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SITE");
    }

    @Test
    void anEmptyOutletStillAnswersWithTheMandatoryFields() throws Exception {
        FeedRenderer.RenderedFeed rendered = render(FeedFormat.ATOM);
        String xml = new String(rendered.body(), StandardCharsets.UTF_8);
        // Nothing has ever been published here, so the timestamp is the epoch rather than now(): stable across
        // requests, and honest about being empty. Last-Modified stays absent for the same reason.
        assertThat(xml).contains("<updated>1970-01-01T00:00:00Z</updated>").doesNotContain("<entry>");
        assertThat(rendered.lastModified()).isNull();
        assertThat(rendered.itemCount()).isZero();
    }

    private FeedRenderer.RenderedFeed render(FeedFormat format) {
        return renderer.render(stub, FeedKey.site(), format, stub.fingerprint(FeedKey.site(), renderer.itemLimit()));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static FeedItem item(String id, String title, String body, String summary) {
        return new FeedItem(id, id, title, summary, body, null, Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-18T00:00:00Z"), List.of(), List.of(), List.of(), null, null, null);
    }
}
