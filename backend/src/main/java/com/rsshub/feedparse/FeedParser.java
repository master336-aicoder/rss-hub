package com.rsshub.feedparse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * The one entry point {@code feedfetch} is allowed to use: bytes and a Content-Type in, {@link NormalizedFeed} out.
 * It owns the order 03 §5.2 specifies — decode first (a mislabelled encoding corrupts everything after it, so the
 * format cannot be sniffed from raw bytes), then sniff the root, then hand the document to the one reader that
 * matches.
 *
 * <p>Stateless, and the underlying jsoup/Jackson parsers are documented as thread-safe, so a single bean is enough
 * for every fetch worker.
 */
public final class FeedParser {

    /** The charset half of a Content-Type value, quoted or not. */
    private static final Pattern CHARSET = Pattern.compile("(?i);\\s*charset\\s*=\\s*\"?([^\";\\s]+)");

    public NormalizedFeed parse(byte[] body, String contentType, String feedUrl) {
        return parse(DocumentDecoder.decode(body, charsetOf(contentType)), feedUrl);
    }

    /** The same path from already-decoded text, which is what the dirty-corpus tests feed it. */
    public NormalizedFeed parse(String text, String feedUrl) {
        if (text == null) {
            throw new FeedParseException("document is null");
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            throw new FeedParseException("document is empty");
        }
        if (bodyIsJsonFeed(trimmed)) {
            return JsonFeedReader.read(trimmed, feedUrl);
        }
        if (!trimmed.startsWith("<")) {
            throw new FeedParseException("document starts with neither '<' nor '{'");
        }
        return readXml(parseXml(trimmed, feedUrl), feedUrl);
    }

    /**
     * Content-Type lies less often than an XML prolog does, but both lie, so whichever the header says is only the
     * first candidate {@link DocumentDecoder} tries.
     */
    static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = CHARSET.matcher(contentType);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean bodyIsJsonFeed(String trimmed) {
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static Document parseXml(String text, String feedUrl) {
        try {
            // The XML parser, never the HTML one: an HTML parser lower-cases, closes tags it invents and reads
            // <![CDATA[...]]> as a bogus comment — dropping the bodies this parser exists to read.
            return Jsoup.parse(text, feedUrl, Parser.xmlParser());
        } catch (RuntimeException e) {
            // jsoup wraps its own IOException in UncheckedIOException, and the XML reader refuses a document whose
            // prefixes are undeclared. Either way the operator needs the reason, not a stack trace of a 10MB file.
            throw new FeedParseException("XML is not well formed: " + e.getClass().getSimpleName());
        }
    }

    private static NormalizedFeed readXml(Document document, String feedUrl) {
        Element root = document.firstElementChild();
        if (root == null) {
            throw new FeedParseException("document has no root element");
        }
        String name = Tags.localName(root);
        switch (name) {
            case "rss":
                return RssLikeReader.read(root, feedUrl, FeedFormat.RSS2);
            case "feed":
                return AtomReader.read(root, feedUrl);
            case "rdf":
                return RssLikeReader.read(root, feedUrl, FeedFormat.RDF);
            case "opml":
                throw new FeedParseException("document is an OPML outline, not a feed");
            case "html":
            case "head":
            case "body":
                throw new FeedParseException("document is an HTML page, not a feed");
            default:
                throw new FeedParseException("unknown root element <" + root.tagName() + ">");
        }
    }
}
