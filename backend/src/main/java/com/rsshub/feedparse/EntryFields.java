package com.rsshub.feedparse;

import com.rsshub.common.Hashing;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

/**
 * The parts every XML flavour needs but expresses differently: 03 §5.3's id ladder, §5.5's body priority, people,
 * categories and enclosures. Kept in one place so the three format readers stay about their formats.
 *
 * <p>Everything here is pure: the same element always yields the same value, which is what lets
 * {@code feedstore} hash the result and compare it against a previous round.
 */
final class EntryFields {

    /** 03 §5.5: {@code content:encoded} first, then the format's own content, then description/summary. */
    record Body(String contentHtml, String summary) {
    }

    /** The id ladder result: the value to store and the rung that produced it. */
    record Id(String value, GuidSource source) {
    }

    private EntryFields() {
    }

    /**
     * 03 §5.3's ladder, walked once so the value and its provenance cannot disagree. The rung order is
     * {@code <guid>} → {@code <id>} → the entry's own link → hash of the stable parts, and the last one is what the
     * health page reports as an unstable id.
     */
    static Id id(Element entry, String feedUrl, String url, String title, Instant publishedAt) {
        String guid = Tags.childText(entry, "guid");
        if (guid != null) {
            return new Id(guid, GuidSource.GUID);
        }
        String atomId = Tags.childText(entry, "id");
        if (atomId != null) {
            return new Id(atomId, GuidSource.ATOM_ID);
        }
        if (url != null) {
            return new Id(url, GuidSource.LINK);
        }
        // 03 §5.3 rule 5: the design hashes feed_id + title + published_at. The parser has no row identity, and
        // the subscription URL is the stable stand-in for it — it cannot change without the feed moving.
        return new Id(GuidSource.FALLBACK_HASH.stored() + ":"
            + Hashing.sha256Hex(feedUrl + "|" + nullToEmpty(title) + "|" + publishedAt), GuidSource.FALLBACK_HASH);
    }

    /** RSS 2.0 says {@code isPermaLink="true"} means the article URL; absent means true, {@code false} means opaque. */
    static boolean guidIsPermaLink(Element entry) {
        Element guid = Tags.child(entry, "guid");
        if (guid == null) {
            return false;
        }
        String flag = guid.attr("isPermaLink").trim();
        return flag.isEmpty() || !"false".equalsIgnoreCase(flag);
    }

    /**
     * The article address: {@code <link>} if it is there, else a permalink guid. Both come back absolute, because
     * {@code entry.url} is what the UI links to and what re-distribution re-publishes.
     */
    static String url(Element entry, String base) {
        String link = Addresses.absolute(base, Tags.childText(entry, "link"));
        if (link != null) {
            return link;
        }
        Element guid = Tags.child(entry, "guid");
        // isPermaLink is only an RSS thing, but a guid that is obviously a URL is still worth linking to when the
        // publisher forgot the <link> element — which is what a feed full of "title only" entries looks like.
        if (guid != null && guidIsPermaLink(entry)) {
            String value = Tags.blankToNull(guid.text().trim());
            if (Addresses.isHttp(value)) {
                return Addresses.absolute(base, value);
            }
        }
        return null;
    }

    /**
     * Assemble one normalised entry from an element that has already been identified as an item, an Atom entry or a
     * JSON Feed object rendered into an element. The two XML readers differ in where the link, the dates and the
     * language come from; everything after that is shared, so the shared part lives once.
     *
     * @param url the entry's own address, already absolutised, or null when the publisher gave none
     */
    static NormalizedEntry assemble(Element entry, String feedUrl, String url, Instant publishedAt, Instant updatedAt,
        String lang) {
        String title = Tags.childText(entry, "title");
        // 03 §5.5: markup inside the body resolves against the article page, not against the feed file.
        Body body = body(entry, url == null ? feedUrl : url);
        Id id = id(entry, feedUrl, url, title, publishedAt);
        return new NormalizedEntry(id.value(), id.source(), url, title, body.summary(), body.contentHtml(),
            authors(entry), categories(entry), enclosures(entry, feedUrl), publishedAt, updatedAt, lang,
            extras(entry));
    }

    static Body body(Element entry, String base) {
        String encoded = rawHtml(entry, "encoded");
        String content = rawHtml(entry, "content");
        String description = rawHtml(entry, "description");
        String summarySource = firstNonNull(rawHtml(entry, "summary"), description);

        String html = firstNonNull(encoded, firstNonNull(content, description));
        return new Body(HtmlSanitizer.clean(html, base), HtmlSanitizer.clean(summarySource, base));
    }

    /**
     * The body as the publisher wrote it, as a string that means HTML. Three shapes have to be told apart, and
     * picking the wrong one either loses the markup or double-decodes it:
     * <ul>
     *   <li>{@code type="xhtml"} (Atom): the payload is real markup inside one {@code <div>} — take that div's
     *       inner html;</li>
     *   <li>{@code type="text"} (Atom): the payload is data that merely contains angle brackets — escape it, or
     *       "a &lt; b" becomes a stray tag;</li>
     *   <li>anything else: if the element has element children the markup is in the tree, so serialise it; if it has
     *       none, the content arrived as CDATA or as escaped entities, and both decode to the same HTML string.</li>
     * </ul>
     * The last case is why {@code text()} is used rather than {@code html()} on a CDATA body: the XML tree writes a
     * CDATA node back as {@code <![CDATA[...]]>}, and the HTML parser that then reads it treats the whole thing as a
     * comment and drops the article.
     */
    static String rawHtml(Element parent, String localName) {
        Element element = Tags.child(parent, localName);
        if (element == null) {
            return null;
        }
        String declared = Tags.attr(element, "type");
        if (declared != null) {
            String type = declared.toLowerCase(Locale.ROOT);
            if (type.contains("xhtml")) {
                Element wrapper = element.children().first();
                return Tags.blankToNull((wrapper == null ? element : wrapper).html());
            }
            if (type.equals("text")) {
                return Tags.blankToNull(Entities.escape(element.text()));
            }
        }
        if (!element.children().isEmpty()) {
            return Tags.blankToNull(element.html());
        }
        return Tags.blankToNull(element.text());
    }

    static List<NormalizedEntry.Author> authors(Element entry) {
        List<NormalizedEntry.Author> found = new ArrayList<>();
        // Atom separates author (required, one) from contributor (optional, many); RSS 2.0 only has author, and
        // RSS 1.0 only has dc:creator. Both shapes appear in both formats, so all four are collected. creator is
        // defined as a plain name, but generators write the same "name (email)" mailbox into it, so it gets the same
        // parse rather than a name that contains an address.
        for (Element author : Tags.children(entry, "author")) {
            found.add(person(author));
        }
        for (Element contributor : Tags.children(entry, "contributor")) {
            found.add(person(contributor));
        }
        for (Element creator : Tags.children(entry, "creator")) {
            found.add(rfc822(Tags.blankToNull(creator.text().trim())));
        }
        for (Element byline : Tags.children(entry, "byline")) {
            found.add(rfc822(Tags.blankToNull(byline.text().trim())));
        }
        found.removeIf(author -> author.name() == null && author.email() == null && author.uri() == null);
        return found;
    }

    private static NormalizedEntry.Author person(Element author) {
        Element name = Tags.child(author, "name");
        if (name != null) {
            // Atom: <author><name/><email/><uri/></author>
            return new NormalizedEntry.Author(Tags.blankToNull(name.text().trim()), textChild(author, "email"),
                textChild(author, "uri"));
        }
        // RSS 2.0: <author> is an RFC 822 mailbox: "robot@example.com (The Robot)".
        return rfc822(Tags.blankToNull(author.text().trim()));
    }

    /**
     * The two orders publishers use for the same phrase. RFC 822 writes {@code robot@example.com (The Robot)} and a
     * dc:creator generator writes {@code A Bot (robot@example.com)}, so which half is the address is decided by the
     * {@code @}, not by the position.
     */
    private static NormalizedEntry.Author rfc822(String value) {
        if (value == null) {
            return new NormalizedEntry.Author(null, null, null);
        }
        int paren = value.indexOf('(');
        if (paren > 0 && value.endsWith(")")) {
            String outside = Tags.blankToNull(value.substring(0, paren).trim());
            String inside = Tags.blankToNull(value.substring(paren + 1, value.length() - 1).trim());
            if (isMailbox(outside)) {
                return new NormalizedEntry.Author(inside, outside, null);
            }
            if (isMailbox(inside)) {
                return new NormalizedEntry.Author(outside, inside, null);
            }
            return new NormalizedEntry.Author(outside, null, null);
        }
        if (isMailbox(value)) {
            return new NormalizedEntry.Author(null, value, null);
        }
        return new NormalizedEntry.Author(value, null, null);
    }

    private static boolean isMailbox(String value) {
        return value != null && value.contains("@") && !value.contains(" ");
    }

    /**
     * RSS 2.0 lets one {@code <category>} hold a comma-separated list, and a {@code domain} attribute that is a
     * taxonomy URL. The term is what readers show, so the domain only ever reaches {@code extras}.
     *
     * <p>{@code dc:subject} is the RSS 1.0 spelling of the same thing and {@code media:keyword} the Media RSS one;
     * both are plain text, both split on commas like a category that was written by a generator which had no
     * structured field to put them in.
     */
    static List<String> categories(Element entry) {
        List<String> found = new ArrayList<>();
        for (Element category : Tags.children(entry, "category")) {
            String term = Tags.blankToNull(category.attr("term"));
            String text = Tags.blankToNull(category.text().trim());
            String value = term == null ? text : term;
            if (value == null) {
                continue;
            }
            addSplit(found, value);
        }
        for (Element subject : Tags.children(entry, "subject")) {
            addSplit(found, Tags.blankToNull(subject.text().trim()));
        }
        for (Element keyword : Tags.children(entry, "keyword")) {
            addSplit(found, Tags.blankToNull(keyword.text().trim()));
        }
        return found;
    }

    private static void addSplit(List<String> target, String value) {
        if (value == null) {
            return;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /** @param base the feed URL, used only when an enclosure is written as a site-relative path. */
    static List<NormalizedEntry.Enclosure> enclosures(Element entry, String base) {
        List<NormalizedEntry.Enclosure> found = new ArrayList<>();
        for (Element link : Tags.children(entry, "link")) {
            if (!"enclosure".equalsIgnoreCase(link.attr("rel"))) {
                continue;
            }
            String href = Addresses.absolute(base, Tags.attr(link, "href"));
            if (href != null) {
                found.add(new NormalizedEntry.Enclosure(href, Tags.attr(link, "type"),
                    parseLong(Tags.attr(link, "length")), null));
            }
        }
        for (Element element : Tags.children(entry, "enclosure")) {
            addIfPresent(found, base, Tags.attr(element, "url"), Tags.attr(element, "type"),
                parseLong(Tags.attr(element, "length")), null);
        }
        // itunes:enclosure carries the url as an attribute and the file name as text; only the url is usable.
        for (Element element : Tags.descendants(entry, "enclosure")) {
            addIfPresent(found, base, Tags.attr(element, "url"), null, null, null);
        }
        // Media RSS: audio/video often only exists as media:content, including inside media:group.
        for (Element element : Tags.descendants(entry, "content")) {
            if (!"media".equalsIgnoreCase(Tags.prefix(element))) {
                continue;
            }
            String url = Tags.attr(element, "url");
            if (url == null) {
                continue;
            }
            String type = Tags.attr(element, "type");
            String medium = Tags.attr(element, "medium");
            if (medium != null && !"audio".equalsIgnoreCase(medium) && !"video".equalsIgnoreCase(medium)) {
                continue;
            }
            found.add(new NormalizedEntry.Enclosure(Addresses.absolute(base, url), type,
                parseLong(Tags.attr(element, "filesize")),
                parseLong(Tags.attr(element, "duration"))));
        }
        for (Element duration : Tags.descendants(entry, "duration")) {
            if (!"itunes".equalsIgnoreCase(Tags.prefix(duration))) {
                continue;
            }
            // This only decorates an enclosure that already exists. A podcast item that announces a duration but no
            // file would otherwise lose the number, so the value also stays in extras — see isMapped.
            Long seconds = FlexibleDates.parseDurationSeconds(duration.text().trim());
            if (seconds != null && !found.isEmpty()) {
                NormalizedEntry.Enclosure first = found.get(0);
                found.set(0, new NormalizedEntry.Enclosure(first.url(), first.mimeType(), first.sizeBytes(),
                    seconds));
            }
        }
        return deduplicate(found);
    }

    private static void addIfPresent(List<NormalizedEntry.Enclosure> target, String base, String url, String type,
        Long size, Long duration) {
        if (url != null) {
            target.add(new NormalizedEntry.Enclosure(Addresses.absolute(base, url), type, size, duration));
        }
    }

    private static List<NormalizedEntry.Enclosure> deduplicate(List<NormalizedEntry.Enclosure> enclosures) {
        Map<String, NormalizedEntry.Enclosure> byUrl = new LinkedHashMap<>();
        for (NormalizedEntry.Enclosure enclosure : enclosures) {
            byUrl.putIfAbsent(enclosure.url(), enclosure);
        }
        return List.copyOf(byUrl.values());
    }

    /**
     * 03 §5.2 line 541: namespaced things the core model does not carry are kept as opaque text and never used for
     * logic. Only prefixed elements are collected — an unprefixed tag is either mapped already or is a publisher's
     * own invention, and both cases are noise the health page does not need.
     */
    static Map<String, String> extras(Element entry) {
        Map<String, String> found = new LinkedHashMap<>();
        for (Element element : entry.getAllElements()) {
            if (element == entry || !Tags.hasPrefix(element)) {
                continue;
            }
            String key = Tags.prefix(element) + ":" + Tags.localName(element);
            String value = Tags.blankToNull(element.text().trim());
            if (value == null || isMapped(key)) {
                continue;
            }
            found.put(key, value.length() > 300 ? value.substring(0, 300) : value);
            if (found.size() >= 32) {
                break;
            }
        }
        return found;
    }

    /** Kept out of extras because they are already core fields; duplicating them would double the row size. */
    private static boolean isMapped(String key) {
        return switch (key) {
            case "content:encoded", "dc:creator", "dc:date", "dc:language", "dc:subject", "atom:link",
                "itunes:author", "dcterms:modified", "dcterms:created", "media:content",
                "media:group", "media:thumbnail" -> true;
            default -> false;
        };
    }

    private static String textChild(Element parent, String localName) {
        return Tags.childText(parent, localName);
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a == null ? b : a;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
