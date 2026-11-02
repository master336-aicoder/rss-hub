package com.rsshub.settings;

import com.rsshub.common.JdbcTimes;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code settings} table V1 created and nothing has used since. One row per key; values are text because the
 * table is schema-free by design — a new knob is a repository row, not a migration.
 */
@Repository
public class SettingsRepository {

    private final JdbcClient db;

    public SettingsRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<String> find(String key) {
        // getString rather than query(String.class): the column is a CLOB, and single-column mapping of a CLOB
        // differs enough between H2 and PostgreSQL that reading it as text here is the portable spelling.
        return db.sql("select setting_value from settings where setting_key = ?")
            .param(key)
            .query((rs, rowNum) -> rs.getString("setting_value"))
            .optional();
    }

    /**
     * Update-then-insert rather than a dialect-specific MERGE/ON CONFLICT: settings are written by one operator,
     * at human frequency, so the portability of the two statements beats the atomicity a single upsert would add.
     */
    public void save(String key, String value) {
        int updated = db.sql("update settings set setting_value = ?, updated_at = ? where setting_key = ?")
            .params(value, JdbcTimes.toSql(Instant.now()), key)
            .update();
        if (updated == 0) {
            db.sql("insert into settings (setting_key, setting_value, updated_at) values (?, ?, ?)")
                .params(key, value, JdbcTimes.toSql(Instant.now()))
                .update();
        }
    }
}
