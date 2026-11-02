package com.rsshub.feedfetch;

import com.rsshub.common.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 03 §4.4: what happens between "the operator pasted a URL" and "there is a feed row". Everything here lives inside
 * {@code feedfetch} because it is the only package allowed to open a socket (02 §3 rule 2) — an adminapi that
 * sniffed HTML for {@code rel=alternate} links with its own HTTP client would be a second, unguarded egress path.
 *
 * <h2>Why the guard is re-checked on every probe</h2>
 * The common-path list is built from the URL the publisher answered, so a redirect can move it to another host
 * between probes. Each attempt goes through {@link HttpFeedFetcher}, which validates and pins per hop; probing a
 * hand-assembled list of URLs with a plain client would reopen exactly the DNS-rebinding hole the fetcher exists to
 * close.
 *
 * <h2>No politeness wait</h2>
 * {@link HostPoliteness} paces the background rounds. Discovery is one operator pressing one button on an address
 * they just typed, and the common-path probe stops at the first hit, so the worst case is eight sequential requests
 * rather than a sustained hammering.
 */
@Component
public class FeedDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(FeedDiscoverer.class);

    /** The types a publisher may answer with when the bytes really are a feed (rss-quickref.md §6). */
    private static final Set<String> FEED_TYPES = Set.of(
        "application/rss+xml", "application/atom+xml", "application/rdf+xml", "application/feed+json",
        "application/xml", "text/xml", "application/json");

    /** 03 §4.4 step 2, in the order the design lists them. */
    private static final List<String> COMMON_PATHS = List.of(
        "/feed", "/feed.xml", "/rss", "/rss.xml", "/atom.xml", "/index.xml", "/feed.json");

    /** 03 §4.4 step 1: when a page offers several feeds, JSON beats Atom beats RSS beats RDF. */
    private static final List<String> PRIORITY = List.of(
        "application/feed+json", "application/atom+xml", "application/rss+xml", "application/rdf+xml");

    /** More than this means the page is a directory, not a blog, and the operator has to choose. */
    private static final int MAX_CANDIDATES = 12;

    private final HttpFeedFetcher fetcher;

    public FeedDiscoverer(HttpFeedFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * @param feedUrl set when the address (or one of its redirects) already is a feed
     * @param candidates what a page offers, when it declares more than one feed
     * @param note why nothing was found; only meaningful when {@code feedUrl} is null and candidates are empty
     */
    public record Discovery(String requestedUrl, String resolvedUrl, String feedUrl, List<Candidate> candidates,
        String note) {

        public boolean found() {
            return feedUrl != null;
        }
    }

    /** One {@code rel="alternate"} link from a page, already absolutised against that page's own URL. */
    public record Candidate(String url, String type, String title) {
    }

    /**
     * Resolves {@code url} into a feed URL.
     *
     * @throws ApiException when the address cannot be read, or the guard refuses it or any hop
     */
    public Discovery discover(String url) {
        HttpFeedFetcher.Response first = get(url);
        if (isFeed(first)) {
            return new Discovery(url, first.finalUrl(), first.finalUrl(), List.of(), null);
        }
        if (!first.isContent()) {
            return new Discovery(url, first.finalUrl(), null, List.of(),
                "answered " + first.status() + " and is not a feed");
        }

        List<Candidate> candidates = isHtmlish(first) ? alternates(first) : List.of();
        if (candidates.size() == 1) {
            // A single offer needs no choice from the operator.
            return new Discovery(url, first.finalUrl(), candidates.get(0).url(), candidates, null);
        }
        if (candidates.size() > 1) {
            return new Discovery(url, first.finalUrl(), best(candidates).url(), candidates, null);
        }

        for (String path : COMMON_PATHS) {
            String probe = URI.create(first.finalUrl()).resolve(path).toString();
            HttpFeedFetcher.Response res = getOrNull(probe);
            if (res != null && isFeed(res)) {
                log.info("discovered a feed at the common path {}", probe);
                return new Discovery(url, first.finalUrl(), res.finalUrl(), List.of(), null);
            }
        }
        return new Discovery(url, first.finalUrl(), null, List.of(),
            isHtmlish(first) ? "no_feed_found" : "not_html_" + describeType(first.contentType()));
    }

    private HttpFeedFetcher.Response get(String url) {
        try {
            return fetcher.fetch(url, null, null);
        } catch (FetchFailedException e) {
            // The kind is safe to show: it is one of a fixed enum of transport outcomes, never a URL or a body.
            throw ApiException.badRequest("discovery_failed", "Could not read that address (" + e.kind() + ")");
        }
    }

    /** A 404 on a probe path is the normal case, not a failure: "this probe found nothing" is all the caller needs. */
    private HttpFeedFetcher.Response getOrNull(String url) {
        try {
            HttpFeedFetcher.Response res = fetcher.fetch(url, null, null);
            return res.isContent() ? res : null;
        } catch (FetchFailedException | ApiException e) {
            return null;
        }
    }

    private boolean isFeed(HttpFeedFetcher.Response res) {
        return res.isContent() && (isFeedType(res.contentType()) || looksLikeFeed(res.body()));
    }

    /**
     * Content-Type is the cheap answer and the body sniff is the fallback, because plenty of publishers serve XML as
     * {@code text/html} or {@code application/octet-stream}. The sniff reads only the first bytes of what is already
     * in memory — it never re-opens a stream, and it is never tried on a response that did not deliver a body.
     */
    private static boolean looksLikeFeed(byte[] body) {
        if (body == null || body.length < 32) {
            return false;
        }
        String head = new String(body, 0, Math.min(body.length, 512), StandardCharsets.UTF_8)
            .trim()
            .replace("\uFEFF", "");
        return head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<feed")
            || head.startsWith("<rdf");
    }

    private static boolean isFeedType(String contentType) {
        return FEED_TYPES.contains(describeType(contentType));
    }

    /** Drops parameters and case: {@code "Application/Atom+XML; charset=utf-8"} is Atom. */
    private static String describeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /** Either the declared type says HTML or the first bytes do — a page with no Content-Type is still a page. */
    private static boolean isHtmlish(HttpFeedFetcher.Response res) {
        String type = describeType(res.contentType());
        return type.endsWith("html") || type.endsWith("htm") || startsWith(res.body(), "<!doctype html")
            || startsWith(res.body(), "<html");
    }

    private static boolean startsWith(byte[] body, String marker) {
        if (body == null || body.length < marker.length() + 8) {
            return false;
        }
        return new String(body, 0, Math.min(body.length, 64), StandardCharsets.UTF_8)
            .trim()
            .toLowerCase(Locale.ROOT)
            .startsWith(marker);
    }

    /** 03 §4.4 step 1 plus step 4: document order, {@code href} resolved against the page URL. */
    private List<Candidate> alternates(HttpFeedFetcher.Response res) {
        Document page = Jsoup.parse(new String(res.body(), StandardCharsets.UTF_8), res.finalUrl());
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> found = new ArrayList<>();
        for (Element link : page.select("link[rel~=alternate][href], a[rel~=alternate][href]")) {
            String href = link.attr("abs:href");
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                // javascript:, mailto: and data: do turn up in rel=alternate; only http(s) is subscribable.
                continue;
            }
            String type = describeType(link.attr("type"));
            type = type.isEmpty() ? guessFromPath(href) : type;
            if (!isFeedType(type) || !seen.add(href) || found.size() >= MAX_CANDIDATES) {
                continue;
            }
            // {@code <link>} is a void element, so the feed's name is in its title attribute, never in its text.
            String name = link.attr("title").isBlank() ? link.text().trim() : link.attr("title").trim();
            found.add(new Candidate(href, type, name.isBlank() ? null : name));
        }
        return found;
    }

    /** A page that declares no type still usually names the file; the path is the only evidence left. */
    private static String guessFromPath(String href) {
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "application/feed+json";
        }
        return lower.contains("atom") ? "application/atom+xml" : "application/rss+xml";
    }

    private static Candidate best(List<Candidate> candidates) {
        Candidate winner = candidates.get(0);
        for (Candidate candidate : candidates) {
            if (rank(candidate.type()) < rank(winner.type())) {
                winner = candidate;
            }
        }
        return winner;
    }

    private static int rank(String type) {
        int index = PRIORITY.indexOf(type);
        return index < 0 ? PRIORITY.size() : index;
    }
}
