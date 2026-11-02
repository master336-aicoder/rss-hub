package com.rsshub.editorial;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The write path for a post body (03 §6.3). Two stages, and the tests check both: CommonMark has to understand the
 * syntax, and the whitelist has to survive a body that was not written by the person who owns the site.
 */
class MarkdownRendererTest {

    @Nested
    @DisplayName("stage one: the syntax an author actually writes")
    class Syntax {

        @Test
        void headingsListsAndLinksBecomeTheirTags() {
            String html = MarkdownRenderer.toHtml("""
                ## 标题

                - 第一项
                - 第二项

                见 [文档](https://example.org/docs)。
                """);
            assertThat(html).contains("<h2>标题</h2>")
                .contains("<ul>")
                .contains("<li>第一项</li>")
                .contains("<a href=\"https://example.org/docs\">文档</a>");
        }

        @Test
        void fencedCodeKeepsItsContentVerbatim() {
            String html = MarkdownRenderer.toHtml("```java\nString s = \"<b>not bold</b>\";\n```\n");
            assertThat(html).contains("<pre><code").contains("&lt;b&gt;not bold&lt;/b&gt;");
        }

        @Test
        void gfmTablesAreOnBecauseNotesCompareOptionsInTables() {
            String html = MarkdownRenderer.toHtml("""
                | 方案 | 成本 |
                | --- | --- |
                | H2 | 0 |
                """);
            assertThat(html).contains("<table>").contains("<th>方案</th>").contains("<td>H2</td>");
        }

        @Test
        void strikethroughIsOnToo() {
            assertThat(MarkdownRenderer.toHtml("~~gone~~")).contains("<del>gone</del>");
        }

        @Test
        void blankInputIsAnEmptyStringNotNull() {
            // The body_html column is NOT NULL downstream and the feed writer must never receive null.
            assertThat(MarkdownRenderer.toHtml(null)).isEmpty();
            assertThat(MarkdownRenderer.toHtml("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("stage two: what the whitelist refuses")
    class Sanitising {

        @Test
        void scriptAndEventHandlerMarkupNeverReachAReader() {
            String html = MarkdownRenderer.toHtml("<script>alert(1)</script><p onclick=\"steal()\">hi</p>");
            // The Cleaner drops a disallowed element together with its content, so the reader sees the plain-text
            // paragraph that was safe and nothing of the script at all.
            assertThat(html).doesNotContain("<script").doesNotContain("onclick").doesNotContain("alert(1)");
            assertThat(html).contains("<p>hi</p>");
        }

        @Test
        void javascriptAndDataUrlsAreStrippedFromLinksAndImages() {
            assertThat(MarkdownRenderer.toHtml("[x](javascript:alert(1))"))
                .doesNotContain("javascript:");
            assertThat(MarkdownRenderer.toHtml("![x](javascript:alert(1))"))
                .doesNotContain("javascript:");
        }

        @Test
        void anIframeIsNotOnTheList() {
            assertThat(MarkdownRenderer.toHtml("<iframe src=\"https://example.org\"></iframe>"))
                .doesNotContain("iframe");
        }

        @Test
        void theTagsAPostNeedsThatAFeedConsumerDoesNotAreAllowedHere() {
            String html = MarkdownRenderer.toHtml("""
                <details><summary>展开</summary>正文</details>

                <video src="https://example.org/a.mp4" controls></video>
                """);
            assertThat(html).contains("<details>").contains("<summary>展开</summary>")
                .contains("<video");
        }

        @Test
        void theResultIsSomethingAFeedCanShipInsideCdata() {
            String html = MarkdownRenderer.toHtml("a & b < c");
            // An ampersand that is still a bare & would make the whole XML document fail to parse downstream.
            assertThat(html).contains("&amp;").doesNotContain(" & b");
        }
    }

    @Nested
    @DisplayName("summarize: the fallback when an author did not write a summary")
    class Summarising {

        @Test
        void tagsAreStrippedAndTextIsReturned() {
            assertThat(MarkdownRenderer.summarize("<p>第一段 <strong>重点</strong></p>", 300)).isEqualTo("第一段 重点");
        }

        @Test
        void codeBlocksAreNotAStudyOfStyleSoTheyLeaveTheSummary() {
            String html = "<p>正文</p><pre>SELECT * FROM huge_table WHERE ...</pre>";
            assertThat(MarkdownRenderer.summarize(html, 300)).isEqualTo("正文");
        }

        @Test
        void aLongBodyIsCutOnAWordBoundaryAndMarked() {
            String summary = MarkdownRenderer.summarize("<p>" + "word ".repeat(200) + "</p>", 60);
            assertThat(summary).endsWith("…").hasSizeLessThanOrEqualTo(62);
            assertThat(summary).doesNotContain("wor…");
        }

        @Test
        void chineseTextHasNoSpacesSoTheCutIsPlain() {
            String text = "句子".repeat(100);
            String summary = MarkdownRenderer.summarize("<p>" + text + "</p>", 20);
            assertThat(summary).isEqualTo(text.substring(0, 20) + "…");
        }

        @Test
        void nothingInIsNothingOut() {
            assertThat(MarkdownRenderer.summarize(null, 300)).isEmpty();
            assertThat(MarkdownRenderer.summarize("<p>   </p>", 300)).isEmpty();
        }
    }
}
