package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link Addresses} decides what the reader clicks and what a re-published feed advertises, so the interesting cases
 * are all the shapes that are not already absolute.
 */
class AddressesTest {

    private static final String FEED = "https://blog.example.com/feed.xml";
    private static final String ENTRY = "https://blog.example.com/2026/post.html";

    @Test
    void resolvesSiteRelativeAndPathRelativeAgainstTheBase() {
        assertThat(Addresses.absolute(FEED, "/2026/post")).isEqualTo("https://blog.example.com/2026/post");
        assertThat(Addresses.absolute("https://blog.example.com/news/feed.xml", "../post"))
            .isEqualTo("https://blog.example.com/post");
        assertThat(Addresses.absolute(ENTRY, "?utm=rss")).isEqualTo("https://blog.example.com/2026/post.html?utm=rss");
        assertThat(Addresses.absolute(ENTRY, "image.png")).isEqualTo("https://blog.example.com/2026/image.png");
    }

    @Test
    void addsTheBaseSchemeToProtocolRelativeAddresses() {
        assertThat(Addresses.absolute("https://blog.example.com/feed.xml", "//cdn.example.com/a.png"))
            .isEqualTo("https://cdn.example.com/a.png");
        assertThat(Addresses.absolute("http://blog.example.com/feed.xml", "//cdn.example.com/a.png"))
            .isEqualTo("http://cdn.example.com/a.png");
    }

    @Test
    void leavesAbsoluteAndUnparsableAddressesAlone() {
        assertThat(Addresses.absolute(FEED, "https://other.example/x?a=b")).isEqualTo("https://other.example/x?a=b");
        assertThat(Addresses.absolute(FEED, "mailto:me@example.com")).isEqualTo("mailto:me@example.com");
        // A space in a URL is invalid to URI but obviously meant as one; guessing a host would be worse.
        assertThat(Addresses.absolute(FEED, "http://example.com/a b")).isEqualTo("http://example.com/a b");
        assertThat(Addresses.absolute(null, "/just/a/path")).isEqualTo("/just/a/path");
    }

    @Test
    void trimsInvisibleCharactersThatWouldBreakTheLink() {
        // A zero-width space is what some publishers insert to defeat link previews; the non-breaking space comes
        // from a double-click that caught the gap after the URL.
        assertThat(Addresses.absolute(FEED, "https://example.com/a​")).isEqualTo("https://example.com/a");
        assertThat(Addresses.absolute(FEED, " https://example.com/b ")).isEqualTo("https://example.com/b");
    }

    @Test
    void returnsNullForNothing() {
        assertThat(Addresses.absolute(FEED, null)).isNull();
        assertThat(Addresses.absolute(FEED, "   ")).isNull();
    }

    @Test
    void recognisesOnlyHttpPermaLinksAsClickThroughAddresses() {
        assertThat(Addresses.isHttp("https://example.com/a")).isTrue();
        assertThat(Addresses.isHttp("http://example.com")).isTrue();
        assertThat(Addresses.isHttp("tag:example.com,2020:/post/1")).isFalse();
        assertThat(Addresses.isHttp("urn:uuid:2f1")).isFalse();
        assertThat(Addresses.isHttp("/relative")).isFalse();
        assertThat(Addresses.isHttp(null)).isFalse();
    }
}
