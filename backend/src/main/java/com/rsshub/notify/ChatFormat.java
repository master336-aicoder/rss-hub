package com.rsshub.notify;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How one alert reads in a chat window. Two shapes, because a DingTalk custom robot's {@code text} message shows
 * line breaks but not markup, while WeCom's {@code markdown} renders a link — and both truncate the same way: the
 * title and the source first, because those are the two things that decide whether the message gets opened.
 */
final class ChatFormat {

    /**
     * A byte ceiling, because that is the unit the vendor publishes: WeCom's group-robot page states its markdown
     * bodies in bytes (「markdown_v2内容，最长不超过4096个字节，必须是utf8编码」,
     * developer.work.weixin.qq.com/document/path/91770, checked 2026-10-18). A character count would be the wrong
     * guard — a CJK alert is about three bytes per character, so 2000 characters can be a 6000-byte body.
     *
     * <p>2000 bytes sits under every per-type figure seen quoted for that page, and the page's own {@code text} and
     * {@code markdown} rows were not readable in this check, so the number is a deliberate margin (经验值) rather
     * than a quoted limit. Truncating is better than discovering the real ceiling as a rejected alert.
     */
    private static final int CONTENT_MAX_BYTES = 2000;

    private static final String ELLIPSIS = "…";

    private ChatFormat() {
    }

    static String plain(Notification alert) {
        StringBuilder text = new StringBuilder();
        text.append('【').append(alert.ruleName()).append("】").append(nvl(alert.title())).append('\n');
        text.append("来源：").append(nvl(alert.feedTitle()));
        if (alert.url() != null) {
            text.append('\n').append(alert.url());
        }
        appendKeywords(text, alert);
        appendSummary(text, alert);
        return cut(text.toString());
    }

    static String markdown(Notification alert) {
        StringBuilder text = new StringBuilder();
        text.append("### ").append(nvl(alert.title())).append("\n");
        text.append("> 规则 ").append(alert.ruleName()).append(" · 来源 ").append(nvl(alert.feedTitle()))
            .append('\n');
        if (alert.url() != null) {
            text.append("[打开原文](").append(alert.url()).append(")\n");
        }
        appendKeywords(text, alert);
        appendSummary(text, alert);
        return cut(text.toString());
    }

    private static void appendKeywords(StringBuilder text, Notification alert) {
        List<String> keywords = alert.matchedKeywords();
        if (!keywords.isEmpty()) {
            text.append("\n命中：").append(String.join("、", keywords));
        }
    }

    private static void appendSummary(StringBuilder text, Notification alert) {
        if (alert.summary() != null && !alert.summary().isBlank()) {
            text.append("\n").append(alert.summary().trim());
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    /**
     * Cuts on whole code points: a byte-for-byte slice can land inside a three-byte Chinese character, and half a
     * character reaches the chat window as mojibake in place of the alert's own tail.
     */
    private static String cut(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= CONTENT_MAX_BYTES) {
            return value;
        }
        int budget = CONTENT_MAX_BYTES - ELLIPSIS.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder out = new StringBuilder(value.length());
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String part = new String(Character.toChars(codePoint));
            int width = part.getBytes(StandardCharsets.UTF_8).length;
            if (used + width > budget) {
                break;
            }
            out.append(part);
            used += width;
            offset += Character.charCount(codePoint);
        }
        return out.append(ELLIPSIS).toString();
    }
}
