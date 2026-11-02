package com.rsshub.feedrender;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The two date grammars the three formats are allowed to use (03 §6.3, verified in doc/rss-guide.md:608 at rule 6).
 *
 * <p>RSS 2.0 inherits RFC 822's date syntax with a four-digit year. The numeric offset form is chosen over the
 * three-letter zone names because RFC 822 also permits abbreviations the grammar defines as *other* offsets — "Z"
 * means -0000 there, not +0000, and "EST" in a feed written by a machine in Berlin is a lie. {@code +0000} is the
 * only spelling that is unambiguous in every reader.
 *
 * <p>Atom and JSON Feed use RFC 3339, which mandates the uppercase {@code T} separator. Pattern letter {@code X}
 * prints a zero offset as the "Z" that means UTC; the lowercase {@code x} would print "+00:00", which is the same
 * instant but not what readers expect to see in an Atom date.
 */
public final class FeedDates {

    private static final DateTimeFormatter RFC_822 =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter RFC_3339 =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).withZone(ZoneOffset.UTC);

    private FeedDates() {
    }

    public static String rfc822(Instant instant) {
        return instant == null ? null : RFC_822.format(instant);
    }

    public static String rfc3339(Instant instant) {
        return instant == null ? null : RFC_3339.format(instant);
    }

    /** HTTP {@code Last-Modified} wants the same grammar as RSS dates, so it comes from here rather than a second formatter. */
    public static String http(Instant instant) {
        return rfc822(instant);
    }
}
