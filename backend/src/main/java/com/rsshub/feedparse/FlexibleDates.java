package com.rsshub.feedparse;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The date ladder. Feeds are wrong about dates more often than about anything else, and 02 §3 puts "坏日期" in the
 * parser's job description: RFC 822 with a two-digit year, no timezone at all, a weekday that is not the real one,
 * a {@code dc:date} that is only a year, and epoch numbers inside an RSS document.
 *
 * <p>Everything returns {@code null} rather than throwing. A date the parser cannot read is a missing date, not a
 * failed fetch — the entry still has to land, and {@code feedstore} falls back to {@code fetched_at} for ordering.
 */
public final class FlexibleDates {

    /**
     * RFC 1123 §5.2.14 is explicit that a receiver must ignore the day-of-week name in an HTTP date, and feeds get
     * it wrong constantly (a publisher that formats "now" in UTC but names the weekday in local time). The name is
     * therefore removed before matching rather than parsed, so "Monday," in front of a Saturday cannot reject the
     * whole value.
     */
    private static final Pattern LEADING_WEEKDAY = Pattern.compile("(?i)^[A-Za-z]+\\s*,\\s*");

    /**
     * The RFC 822/1123 family: optional weekday (already stripped), one or two digit day, short month name,
     * four-digit year, optional time where seconds may be missing, then one of GMT / UTC / EST / +0800 / -04:00 / Z
     * / nothing. A missing zone means UTC ({@link #toInstant}).
     *
     * <p>{@code H:m[:s]} rather than {@code HH:mm:ss}: java.time's letter count is an exact width in strict mode,
     * and "9:03" is a thing publishers write.
     */
    private static final DateTimeFormatter HTTP = zonePart(caseInsensitiveBuilder()
        .appendPattern("d MMM yyyy[ H:m[:s]]")).toFormatter(Locale.US);

    /** RFC 850 ("Sunday, 18-Oct-26 02:00:00 GMT"), still emitted by a few ancient mirrors and by Apache's mod_status. */
    private static final DateTimeFormatter RFC850 = zonePart(caseInsensitiveBuilder()
        .appendPattern("d-MMM-yy[ H:m[:s]]")).toFormatter(Locale.US);

    /** The same dashed shape with a four-digit year, which is what a hand-patched RFC 850 template produces. */
    private static final DateTimeFormatter DASHED_LONG = zonePart(caseInsensitiveBuilder()
        .appendPattern("d-MMM-yyyy[ H:m[:s]]")).toFormatter(Locale.US);

    /** "2026-10-18 09:00:00" and "2026-10-18 9:00", the shapes hand-rolled publishers write. */
    private static final DateTimeFormatter SPACE_SEPARATED = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("yyyy-M-d H:m")
        .optionalStart().appendPattern(":s").optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .optionalStart().appendLiteral(' ').appendOffset("+HHmm", "+0000").optionalEnd()
        .toFormatter(Locale.ROOT);

    private static final Pattern EPOCH_SECONDS = Pattern.compile("^\\d{9,10}$");
    private static final Pattern EPOCH_MILLIS = Pattern.compile("^\\d{13,14}$");

    /** Below this, a bare number is a number, not a timestamp (a 9-digit epoch would be 1976). */
    private static final Instant EARLIEST_PLAUSIBLE = Instant.parse("1995-01-01T00:00:00Z");

    private FlexibleDates() {
    }

    /** Case-insensitivity has to be declared before the patterns that need it; the builder applies it from then on. */
    private static DateTimeFormatterBuilder caseInsensitiveBuilder() {
        return new DateTimeFormatterBuilder().parseCaseInsensitive();
    }

    /**
     * Every spelling of "which zone" that a feed has been seen to use. Each variant is optional and preceded by its
     * own literal space, because {@code appendZoneText} refuses to match a name it cannot resolve and the whole
     * pattern must still parse with the zone missing.
     */
    private static DateTimeFormatterBuilder zonePart(DateTimeFormatterBuilder builder) {
        return builder
            .optionalStart().appendLiteral(' ').appendZoneText(TextStyle.SHORT, Set.of(ZoneOffset.UTC)).optionalEnd()
            .optionalStart().appendLiteral(' ').appendOffsetId().optionalEnd()
            .optionalStart().appendLiteral(' ').appendOffset("+HHmm", "0000").optionalEnd();
    }

    /** Best effort, never throws, never invents a date. */
    public static Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (looksLikeATrailingPeriodOnADate(value)) {
            value = value.substring(0, value.length() - 1);
        }
        value = LEADING_WEEKDAY.matcher(value).replaceFirst("");

        Instant epoch = fromEpoch(value);
        if (epoch != null) {
            return epoch;
        }
        Instant iso = fromIsoish(value);
        if (iso != null) {
            return iso;
        }
        for (DateTimeFormatter formatter : List.of(HTTP, RFC850, DASHED_LONG, SPACE_SEPARATED)) {
            Instant parsed = tryPattern(formatter, value);
            if (parsed != null) {
                return parsed;
            }
        }
        return fromPartialYear(value);
    }

    private static boolean looksLikeATrailingPeriodOnADate(String value) {
        // "2026-10-18 09:00:00." — a sentence-ending period, common in hand-written Japanese publishers.
        return value.length() > 2 && value.endsWith(".")
            && Character.isDigit(value.charAt(value.length() - 2));
    }

    /** "2026" and "2026-10" are real {@code dc:date} values and mean the start of that period, in UTC. */
    private static Instant fromPartialYear(String value) {
        if (value.matches("\\d{4}")) {
            return Year.parse(value).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (value.matches("\\d{4}-\\d{1,2}")) {
            return LocalDate.parse(value + "-1", DateTimeFormatter.ofPattern("yyyy-M-d"))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return null;
    }

    private static Instant fromEpoch(String value) {
        if (EPOCH_MILLIS.matcher(value).matches()) {
            return plausible(Instant.ofEpochMilli(Long.parseLong(value)));
        }
        if (EPOCH_SECONDS.matcher(value).matches()) {
            return plausible(Instant.ofEpochSecond(Long.parseLong(value)));
        }
        return null;
    }

    private static Instant fromIsoish(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // not an ISO timestamp with an offset
        }
        try {
            return ZonedDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // not ISO with a named zone
        }
        try {
            return toInstant(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (DateTimeParseException ignored) {
            // not ISO without a zone
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant tryPattern(DateTimeFormatter formatter, String value) {
        try {
            return toInstant(formatter.parse(value));
        } catch (DateTimeException e) {
            // DateTimeParseException is a DateTimeException, and so is "no such day of week"; both mean unreadable.
            return null;
        }
    }

    /**
     * A parsed date with no zone is not an error, it is a feed that forgot to say. UTC is the only reproducible
     * answer: guessing the local zone would make the same document mean different things on two machines.
     */
    private static Instant toInstant(TemporalAccessor parsed) {
        if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
            return Instant.from(parsed);
        }
        try {
            return LocalDateTime.from(parsed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return LocalDate.from(parsed).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static Instant plausible(Instant instant) {
        return instant.isBefore(EARLIEST_PLAUSIBLE) ? null : instant;
    }

    /**
     * Enclosure durations come in four shapes: ISO 8601 ("PT1H30M"), a clock ("1:30:00" or "90:00"), bare seconds,
     * and — for {@code itunes:duration} — minutes only. Returns null when none of those match.
     */
    public static Long parseDurationSeconds(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.toUpperCase(Locale.ROOT).startsWith("PT")) {
            try {
                return Duration.parse(value).getSeconds();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        String[] parts = value.split(":");
        try {
            if (parts.length == 1) {
                return (long) Double.parseDouble(parts[0]);
            }
            long[] numbers = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = (long) Double.parseDouble(parts[i]);
            }
            // H:M:S, M:S and a bare minute count are all in the wild; the longest form is the reference.
            switch (numbers.length) {
                case 3:
                    return numbers[0] * 3600 + numbers[1] * 60 + numbers[2];
                case 2:
                    return numbers[0] * 60 + numbers[1];
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
