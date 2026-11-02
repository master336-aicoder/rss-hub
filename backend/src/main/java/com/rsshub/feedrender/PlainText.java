package com.rsshub.feedrender;

import org.jsoup.Jsoup;

/**
 * A short plain-text preview, used when a source has a body but no summary. Stripping tags here is a presentation
 * choice, so it does not reuse {@code feedparse.HtmlSanitizer}: that class is the ingestion security boundary and is
 * not allowed to be reachable from the render path (02 §3 rule 1). The input is already sanitized before it reached
 * the database.
 */
public final class PlainText {

    /** Roughly one paragraph of an inbox list; long enough to be useful, short enough not to replace the body. */
    private static final int PREVIEW_LENGTH = 300;

    private PlainText() {
    }

    public static String preview(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = Jsoup.parseBodyFragment(html).text().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH).strip() + "…";
    }
}
