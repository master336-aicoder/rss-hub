package com.rsshub.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The one timestamp representation that is correct under both dialects we run.
 * <p>
 * {@code Instant} is refused by the Postgres JDBC driver when binding a parameter, {@code Timestamp} loses the
 * offset on the way out of an H2 {@code TIMESTAMP WITH TIME ZONE} column, and {@code OffsetDateTime} is accepted
 * by both on the write side and returned by both on the read side. So every repository converts at the boundary
 * and nothing in the domain layers sees a JDBC type.
 */
public final class JdbcTimes {

    private JdbcTimes() {
    }

    /**
     * Both dialects store microseconds, and a coarser write rounds up: an {@code Instant} whose nanoseconds are
     * {@code ...534600} becomes {@code ...535000} on the way in, so a row armed for "now" reads back as not due yet
     * a microsecond later. Truncating here keeps a stored instant never greater than the one handed to it, which is
     * what "fire_at <= now" needs to mean for a task armed for the current moment.
     */
    public static OffsetDateTime toSql(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    /** {@code null} stays {@code null}; a null here always means "this has never happened", never "unknown". */
    public static Instant fromSql(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
