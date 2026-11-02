package com.rsshub.editorial;

import com.rsshub.common.ApiException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Slugs are permanent URLs, and a permanent URL that changes is a broken archive (FR-1.1; the DDL comment in
 * 03 §3.6 says the same thing as 改则 301). Three consequences are enforced here rather than in review:
 *
 * <p>1. A slug is derived from the title when the author did not type one, and normalised hard: lowercase, NFKD so
 * full-width ASCII collapses, spaces to hyphens, nothing that a URL would have to percent-encode.
 *
 * <p>2. Non-ASCII letters survive. "2026 年计划" keeps the ideographs rather than degrading to "2026" — they are
 * legal in a path segment, and silently dropping the only meaningful words in a title is worse than a URL with them.
 *
 * <p>3. Once a post exists, the slug has no write path. {@code PostRepository} never sets the column after INSERT,
 * so immutability is a property of the SQL rather than a check someone has to remember.
 */
public final class SlugPolicy {

    private static final int MAX_LENGTH = 200;

    /**
     * Characters a path segment may not carry: separators and query syntax would change which resource the URL
     * addresses, and control characters, quotes, spaces and percent are all things the encoder has to eat.
     */
    private static final String FORBIDDEN = "/\\?#%&+ \t\n\r\"'<>`^{}[]|";

    private SlugPolicy() {
    }

    /**
     * @param raw   what the author typed, or blank to derive from the title
     * @param title the fallback source
     * @throws ApiException when nothing usable survives, which a title of "???" genuinely produces
     */
    public static String derive(String raw, String title) {
        String slug = normalize(raw == null || raw.isBlank() ? title : raw);
        if (slug.isEmpty()) {
            throw ApiException.badRequest("invalid_slug",
                "Neither the slug nor the title contains characters a URL can carry");
        }
        return slug;
    }

    /** Whether a stored or typed slug is in the shape {@link #derive} produces; used to reject an edit early. */
    public static boolean isLegal(String slug) {
        if (slug == null || slug.isEmpty() || slug.length() > MAX_LENGTH) {
            return false;
        }
        if (slug.startsWith("-") || slug.endsWith("-") || slug.startsWith(".") || slug.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            if (FORBIDDEN.indexOf(c) >= 0 || c <= 0x1f || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (c >= 0x80 && !Character.isWhitespace(c)) {
                // Keep a letter that has no ASCII form; drop the combining marks NFKD just produced from an accent.
                if (!Character.isIdeographic(c) && Character.getType(c) == Character.NON_SPACING_MARK) {
                    continue;
                }
                out.append(c);
                continue;
            }
            char ascii = Character.toLowerCase(c);
            if (Character.isLetterOrDigit(ascii)) {
                out.append(ascii);
            } else if (ascii == '.' || ascii == '_') {
                out.append(ascii);
            } else {
                // Everything else — spaces, punctuation, slashes, the percent sign — becomes a separator or nothing.
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                    out.append('-');
                }
            }
        }
        String slug = out.toString().replaceAll("^-+", "");
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }

    /** Case-folded comparison for the uniqueness check, so "My-Post" and "my-post" cannot both be created. */
    public static String key(String slug) {
        return slug.toLowerCase(Locale.ROOT);
    }
}
