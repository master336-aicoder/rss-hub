package com.rsshub.feedparse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The date ladder from 03 §5.4. Every case here is a shape a real feed has been seen to use, and the contract is
 * that none of them throw: an unreadable date is a missing date.
 */
class FlexibleDatesTest {

    @Test
    void readsRfc822WithFourDigitYearAndZoneAbbreviation() {
        assertThat(FlexibleDates.parse("Sun, 18 Oct 2026 09:30:00 GMT"))
            .isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(FlexibleDates.parse("Sun, 18 Oct 2026 09:30:00 +0800"))
            .isEqualTo(Instant.parse("2026-10-18T01:30:00Z"));
        assertThat(FlexibleDates.parse("Sun, 18 Oct 2026 09:30:00 -04:00"))
            .isEqualTo(Instant.parse("2026-10-18T13:30:00Z"));
    }

    @Test
    void toleratesTheCommonRfc822Sloppiness() {
        // no weekday
        assertThat(FlexibleDates.parse("18 Oct 2026 09:30:00 GMT")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        // lower-case month and zone
        assertThat(FlexibleDates.parse("sun, 18 oct 2026 09:30:00 gmt"))
            .isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        // single-digit day, and the UTC spelling
        assertThat(FlexibleDates.parse("8 Oct 2026 9:03:00 UTC")).isEqualTo(Instant.parse("2026-10-08T09:03:00Z"));
        // no seconds
        assertThat(FlexibleDates.parse("8 Oct 2026 9:03 GMT")).isEqualTo(Instant.parse("2026-10-08T09:03:00Z"));
    }

    @Test
    void ignoresAWrongDayOfWeekInsteadOfRejectingTheDate() {
        // 2026-10-18 really is a Sunday; RFC 1123 §5.2.14 tells a receiver to discard the day name, and publishers
        // format the two halves in different zones.
        assertThat(FlexibleDates.parse("Monday, 18 Oct 2026 09:30:00 GMT"))
            .isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(FlexibleDates.parse("Fri, 18 Oct 2026 09:30:00 +0000"))
            .isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
    }

    @Test
    void readsRfc850TwoDigitYearForms() {
        assertThat(FlexibleDates.parse("Sunday, 18-Oct-26 02:00:00 GMT"))
            .isEqualTo(Instant.parse("2026-10-18T02:00:00Z"));
    }

    @Test
    void readsIso8601WithOffsetZoneAndPlainDateTime() {
        assertThat(FlexibleDates.parse("2026-10-18T09:30:00+08:00")).isEqualTo(Instant.parse("2026-10-18T01:30:00Z"));
        assertThat(FlexibleDates.parse("2026-10-18T09:30:00Z")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        // no zone at all: UTC, because that is the only answer two machines can agree on
        assertThat(FlexibleDates.parse("2026-10-18T09:30:00")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(FlexibleDates.parse("2026-10-18T09:30:00.123456Z"))
            .isEqualTo(Instant.parse("2026-10-18T09:30:00.123456Z"));
        assertThat(FlexibleDates.parse("2026-10-18")).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void readsSpaceSeparatedHandWrittenDates() {
        assertThat(FlexibleDates.parse("2026-10-18 09:30:00")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(FlexibleDates.parse("2026-10-18 9:30")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
        assertThat(FlexibleDates.parse("2026-10-18 09:30:00 +0800"))
            .isEqualTo(Instant.parse("2026-10-18T01:30:00Z"));
    }

    @Test
    void readsEpochSecondsAndMillisButRefusesPreWebDates() {
        assertThat(FlexibleDates.parse("1760780000")).isEqualTo(Instant.ofEpochSecond(1_760_780_000L));
        assertThat(FlexibleDates.parse("1760780000000")).isEqualTo(Instant.ofEpochMilli(1_760_780_000_000L));
        // 8 digits or fewer is a number, not a timestamp: "2026" alone is handled as a year below.
        assertThat(FlexibleDates.parse("12345678")).isNull();
    }

    @Test
    void readsPartialDatesThatOnlySayAYearOrAMonth() {
        assertThat(FlexibleDates.parse("2026")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(FlexibleDates.parse("2026-10")).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void refusesToGuessWhenTheValueIsNotADateAtAll() {
        assertThat(FlexibleDates.parse(null)).isNull();
        assertThat(FlexibleDates.parse("")).isNull();
        assertThat(FlexibleDates.parse("   ")).isNull();
        assertThat(FlexibleDates.parse("刚刚")).isNull();
        assertThat(FlexibleDates.parse("2 hours ago")).isNull();
        assertThat(FlexibleDates.parse("not-a-date")).isNull();
    }

    @Test
    void dropsASentenceEndingPeriodFromADate() {
        assertThat(FlexibleDates.parse("2026-10-18 09:30:00.")).isEqualTo(Instant.parse("2026-10-18T09:30:00Z"));
    }

    @Test
    void readsEnclosureDurationsInEveryShapePublishersUse() {
        assertThat(FlexibleDates.parseDurationSeconds("3600")).isEqualTo(3600L);
        assertThat(FlexibleDates.parseDurationSeconds("1:00:00")).isEqualTo(3600L);
        assertThat(FlexibleDates.parseDurationSeconds("60:00")).isEqualTo(3600L);
        assertThat(FlexibleDates.parseDurationSeconds("90")).isEqualTo(90L);
        assertThat(FlexibleDates.parseDurationSeconds("PT1H30M")).isEqualTo(5400L);
        assertThat(FlexibleDates.parseDurationSeconds("1:02:03")).isEqualTo(3723L);
        assertThat(FlexibleDates.parseDurationSeconds("")).isNull();
        assertThat(FlexibleDates.parseDurationSeconds("long")).isNull();
        assertThat(FlexibleDates.parseDurationSeconds("PT bogus")).isNull();
    }
}
