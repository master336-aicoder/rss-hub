package com.rsshub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * There is no Postgres in this workspace and no CI that runs both dialects (03 §14 O13), so the only thing standing
 * between a repository change and a production-only crash is this file-for-file comparison.
 *
 * <p>Types are deliberately <em>not</em> compared: {@code CLOB}/{@code JSONB}, {@code VARCHAR(n)}/{@code TEXT} and
 * {@code TIMESTAMP WITH TIME ZONE}/{@code TIMESTAMPTZ} are the intentional deviations the migration headers
 * describe. Column <em>names</em> are what the application's SQL is written against, and a name that exists in only
 * one dialect is the bug that would otherwise wait until the first production fetch to show up.
 */
class MigrationDialectAlignmentTest {

    /** Columns Postgres has and H2 cannot: the generated tsvector behind full-text search. */
    private static final Set<String> PG_ONLY_COLUMNS = Set.of("entry.search_ts");

    /**
     * Every migration is compared, not just the baseline: a table that only exists in one dialect is the same bug
     * whether it arrived in V1 or in V3.
     */
    private static final List<String> MIGRATIONS = List.of("V1__baseline_schema.sql", "V2__publishing_schema.sql",
        "V3__events_rules_schema.sql", "V4__repack_schema.sql", "V5__changelog_schema.sql",
        "V6__alert_filter_index.sql", "V7__feed_editorial_controls.sql");

    private static final String H2 = "/db/migration/h2/V1__baseline_schema.sql";
    private static final String PG = "/db/migration/postgresql/V1__baseline_schema.sql";

    private static String h2(String migration) {
        return "/db/migration/h2/" + migration;
    }

    private static String pg(String migration) {
        return "/db/migration/postgresql/" + migration;
    }

    @Test
    void bothDialectsCreateTheSameTablesInTheSameOrder() {
        for (String migration : MIGRATIONS) {
            assertThat(parse(h2(migration)).keySet())
                .as("%s must create the same tables in the same order in both dialects", migration)
                .isEqualTo(parse(pg(migration)).keySet());
        }
    }

    @Test
    void everyColumnMatchesBetweenTheTwoDialects() {
        for (String migration : MIGRATIONS) {
            Map<String, Table> h2 = parse(h2(migration));
            Map<String, Table> pg = parse(pg(migration));

            for (String table : h2.keySet()) {
                List<String> h2Columns = h2.get(table).columns;
                List<String> pgColumns = new ArrayList<>(pg.get(table).columns);
                pgColumns.removeAll(PG_ONLY_COLUMNS.stream()
                    .filter(qualified -> qualified.startsWith(table + "."))
                    .map(qualified -> qualified.substring(table.length() + 1))
                    .toList());
                assertThat(pgColumns)
                    .as("table %s must have the same columns in both dialects", table)
                    .isEqualTo(h2Columns);
            }
        }
    }

    @Test
    void theDeviationListIsNotHoldingAColumnThatNoLongerExists() {
        Map<String, Table> pg = parse(PG);
        for (String qualified : PG_ONLY_COLUMNS) {
            String table = qualified.substring(0, qualified.indexOf('.'));
            String column = qualified.substring(table.length() + 1);
            assertThat(pg.get(table).columns)
                .as("%s is declared Postgres-only, so it must still be in the Postgres file", qualified)
                .contains(column);
            assertThat(parse(H2).get(table).columns).doesNotContain(column);
        }
    }

    @Test
    void theUniqueConstraintsTheIngestRulesDependOnExistInBothDialects() {
        String h2 = read(H2).toLowerCase(Locale.ROOT);
        String pg = read(PG).toLowerCase(Locale.ROOT);
        // 03 §5.3/§5.4: entry upserts on (feed_id, guid), the queue dedupes on (kind, dedupe_key).
        for (String constraint : List.of("uq_entry_feed_guid", "uq_task_kind_dedupe")) {
            assertThat(h2).contains(constraint);
            assertThat(pg).contains(constraint);
        }
    }

    /**
     * V7 adds columns to an existing table and creates nothing, so the table/column comparison above is blind to it
     * (both sides parse to an empty table set). {@code ALTER TABLE ... ADD COLUMN} is the one statement shape the
     * parser can read without a table body, so the columns it introduces get their own file-for-file check rather
     * than being silently exempt from alignment — which is exactly the class of bug this test exists to catch.
     */
    @Test
    void addedColumnsMatchBetweenTheTwoDialects() {
        for (String migration : MIGRATIONS) {
            assertThat(addedColumns(h2(migration)))
                .as("%s must add the same columns in both dialects", migration)
                .isEqualTo(addedColumns(pg(migration)));
        }
    }

    private static List<String> addedColumns(String resource) {
        List<String> columns = new ArrayList<>();
        Matcher line = Pattern.compile("(?i)^\\s*ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+(?:COLUMN\\s+)?(\\w+)\\b")
            .matcher(read(resource));
        while (line.find()) {
            columns.add(line.group(1).toLowerCase(Locale.ROOT) + "." + line.group(2).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static Map<String, Table> parse(String resource) {
        Map<String, Table> tables = new LinkedHashMap<>();
        Table current = null;
        int depth = 0;
        for (String rawLine : read(resource).split("\\R")) {
            String line = rawLine.trim();
            int comment = line.indexOf("--");
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.regionMatches(true, 0, "CREATE TABLE", 0, 12)) {
                current = new Table(line.substring(12).replace("(", "").trim().toLowerCase(Locale.ROOT));
                tables.put(current.name, current);
                depth = 1;
                continue;
            }
            if (current == null) {
                continue;
            }
            // A column line starts with the column's own name. Continuation lines of a wrapped CHECK or of the
            // generated tsvector expression start with a bracket or a quote, and table clauses are filtered below.
            if (depth == 1 && Character.isLetter(line.charAt(0)) && !isConstraint(line)) {
                current.columns.add(line.split("[\\s(]")[0].toLowerCase(Locale.ROOT));
            }
            depth += count(line, '(') - count(line, ')');
            if (depth <= 0) {
                current = null;
            }
        }
        return tables;
    }

    private static int count(String line, char marker) {
        int occurrences = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == marker) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /** Table-level clauses, written on their own line in both files, are not columns. */
    private static boolean isConstraint(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY") || upper.startsWith("UNIQUE")
            || upper.startsWith("FOREIGN") || upper.startsWith("CHECK");
    }

    private static String read(String resource) {
        try (InputStream in = MigrationDialectAlignmentTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing migration " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Table {
        private final String name;
        private final List<String> columns = new ArrayList<>();

        private Table(String name) {
            this.name = name;
        }
    }
}
