package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 03 §5.5: the whitelist, the tracking-pixel rule, the embed rule, and the hardening of every link. These are the
 * guarantees the reader UI relies on when it injects {@code entry.content_html} with {@code v-html}.
 */
class HtmlSanitizerTest {

    @Test
    void keepsTheAllowedMarkupAndDropsTheRest() {
        String cleaned = HtmlSanitizer.clean("<p>Keep <strong>this</strong> and <em>this</em></p>"
            + "<script>alert(1)</script><style>p{color:red}</style><iframe src='https://evil.example'></iframe>", null);
        assertThat(cleaned).contains("<p>Keep <strong>this</strong> and <em>this</em></p>");
        assertThat(cleaned).doesNotContain("script", "alert", "color:red", "iframe", "evil.example");
    }

    @Test
    void dropsEventHandlerAndStyleAttributes() {
        String cleaned = HtmlSanitizer.clean("<p onclick=\"steal()\" style=\"position:fixed\" class=\"x\">text</p>",
            null);
        assertThat(cleaned).isEqualTo("<p>text</p>");
    }

    @Test
    void refusesJavascriptAndDataUrls() {
        assertThat(HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">x</a>", null)).doesNotContain("javascript");
        assertThat(HtmlSanitizer.clean("<img src=\"data:image/svg+xml;base64,AAA\">", null)).doesNotContain("data:");
    }

    @Test
    void absolutisesRelativeAddressesAgainstTheEntryBase() {
        String cleaned = HtmlSanitizer.clean("<p><a href=\"/a\">l</a> <img src=\"/i.png\"></p>",
            "https://blog.example.com/2026/post");
        assertThat(cleaned).contains("https://blog.example.com/a").contains("https://blog.example.com/i.png");
    }

    @Test
    void hardensEveryLinkForANewTabWithoutLettingItTouchTheOpener() {
        String cleaned = HtmlSanitizer.clean("<a href=\"https://example.com/x\" target=\"_top\" rel=\"me\">x</a>",
            null);
        assertThat(cleaned).contains("target=\"_blank\"").contains("rel=\"noopener noreferrer\"");
        assertThat(cleaned).doesNotContain("_top").doesNotContain("rel=\"me\"");
    }

    @Test
    void keepsOnlyWhitelistedEmbedsAndSandboxesThem() {
        String youtube = HtmlSanitizer.clean("<iframe src=\"https://www.youtube.com/embed/abc\"></iframe>", null);
        assertThat(youtube).contains("<iframe").contains("sandbox=\"allow-scripts allow-same-origin allow-popups\"");
        assertThat(HtmlSanitizer.clean("<iframe src=\"https://player.example.com/x\"></iframe>", null)).isNull();
    }

    @Test
    void removesTrackingPixelsBySizeHostAndPath() {
        assertThat(HtmlSanitizer.clean("<img src=\"https://example.com/a.png\" width=\"1\" height=\"1\">", null)).isNull();
        assertThat(HtmlSanitizer.clean("<img src=\"https://mailchimp.com/track/open?url=x\">", null)).isNull();
        assertThat(HtmlSanitizer.clean("<img src=\"\">", null)).isNull();
        assertThat(HtmlSanitizer.clean("<img src=\"https://example.com/real.png\" width=\"640\">", null))
            .contains("real.png");
    }

    @Test
    void keepsTablesFiguresAndCodeButNotTheirScript() {
        String cleaned = HtmlSanitizer.clean("<table><tr><td colspan=\"2\">c</td></tr></table>"
            + "<figure><img src=\"https://e/x.png\" alt=\"a\"><figcaption>cap</figcaption></figure>"
            + "<pre><code class=\"java\">int i = 1;</code></pre>", null);
        assertThat(cleaned).contains("<table>", "<figure>", "<figcaption>cap</figcaption>", "<pre>", "int i = 1;");
    }

    @Test
    void returnsNullWhenNothingSurvives() {
        assertThat(HtmlSanitizer.clean("<script>only</script>", null)).isNull();
        assertThat(HtmlSanitizer.clean("   ", null)).isNull();
        assertThat(HtmlSanitizer.clean(null, null)).isNull();
    }

    @Test
    void escapesNothingTwiceWhenTheInputAlreadyHoldsMarkup() {
        // A feed body whose angle brackets are data, not tags, must not turn into a stray tag in the reader.
        String cleaned = HtmlSanitizer.clean(HtmlSanitizer.clean("a &lt; b", null), null);
        assertThat(cleaned).contains("a &lt; b").doesNotContain("<b");
    }

    @Test
    void textStripsMarkupAndKeepsWords() {
        assertThat(HtmlSanitizer.text("<p>Hello<br>world</p>")).isEqualTo("Hello\nworld");
        assertThat(HtmlSanitizer.text("<p>a   b</p>")).isEqualTo("a b");
        assertThat(HtmlSanitizer.text("<p>&amp;&nbsp;x</p>")).isEqualTo("& x");
        assertThat(HtmlSanitizer.text(null)).isNull();
    }
}
