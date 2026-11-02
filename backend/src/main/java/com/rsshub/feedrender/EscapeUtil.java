package com.rsshub.feedrender;

/**
 * The single escaper behind 03 §6.3 rules 2 and 4. Both are cheap to get wrong and fatal when you do: a bare {@code &}
 * in a query string ("the #1 fatal error", doc/rss-guide.md:612) breaks the whole document, and an unterminated CDATA
 * breaks everything after it.
 *
 * <p>Text nodes escape {@code &} and {@code <} — the two XML requires there. Attributes additionally escape {@code "}
 * because feed URLs carry query strings and a quote would end the attribute early.
 */
public final class EscapeUtil {

    private EscapeUtil() {
    }

    public static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf('&') < 0 && value.indexOf('<') < 0 && value.indexOf('>') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String attribute(String value) {
        if (value == null) {
            return "";
        }
        String escaped = text(value);
        if (escaped.indexOf('"') < 0 && escaped.indexOf('\'') < 0) {
            return escaped;
        }
        return escaped.replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Wraps in CDATA and splits any {@code ]]>} so the sequence can never close the section early. A body containing
     * {@code ]]>} is legal content (code samples do it constantly), so the writer has to survive it rather than
     * reject the post.
     */
    public static String cdata(String value) {
        if (value == null || value.isEmpty()) {
            return "<![CDATA[]]>";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append("<![CDATA[");
        int from = 0;
        int cut;
        // "]]>" is the only sequence that can end the section early, and inside CDATA entities are literal text, so
        // the fix is structural: close the section after the two brackets and reopen it before the ">".
        // "X]]>Y" becomes <![CDATA[X]]]]><![CDATA[>Y]]>, which parses back to exactly "X]]>Y".
        while ((cut = value.indexOf("]]>", from)) >= 0) {
            out.append(value, from, cut + 2).append("]]><![CDATA[");
            from = cut + 2;
        }
        out.append(value, from, value.length()).append("]]>");
        return out.toString();
    }

    /** An element with escaped text content, or the empty string when the value is absent (feed writers omit, never blank). */
    public static String element(String name, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return '<' + name + '>' + text(value) + "</" + name + '>';
    }
}
