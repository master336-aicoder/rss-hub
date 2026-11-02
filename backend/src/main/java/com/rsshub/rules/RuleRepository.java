package com.rsshub.rules;

import com.rsshub.common.ApiException;
import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The {@code rule} table (03 §5's DDL, FR-5.1/5.2/5.3). The four JSON columns travel as text: this repository owns
 * rows, and decoding them is {@link MatchSpec}'s, {@link Action}'s and {@link Suppression}'s business.
 */
@Repository
public class RuleRepository {

    /** The reason prefix that marks a rule the breaker stopped; {@link #countAutoDisabled()} matches on it. */
    public static final String BREAKER_PREFIX = "delivery failed";

    /**
     * {@code rule.suppression} is {@code NOT NULL} with a DDL default, and an insert that names the column with an
     * explicit NULL defeats a default — so the repository supplies the same document here that the schema would.
     * {@link Suppression#parse} reads an absent column as the same policy, so the two never disagree.
     */
    static final String DEFAULT_SUPPRESSION = "{\"key\":\"perFeed\",\"hours\":6}";

    private static final String COLUMNS = "id, name, enabled, match, actions, suppression, quiet_hours, hit_count, "
        + "disabled_reason, created_at, updated_at";

    private static final RowMapper<StoredRule> ROW = (ResultSet rs, int rowNum) -> map(rs);

    private final JdbcClient db;

    public RuleRepository(JdbcClient db) {
        this.db = db;
    }

    /** One row as stored. {@code matchJson} and friends are the raw columns; see {@link RuleJson}. */
    public record StoredRule(long id, String name, boolean enabled, String matchJson, String actionsJson,
        String suppressionJson, String quietHoursJson, int hitCount, String disabledReason, Instant createdAt,
        Instant updatedAt) {
    }

    public List<StoredRule> list() {
        return db.sql("select " + COLUMNS + " from rule order by id desc").query(ROW).list();
    }

    /**
     * What the evaluator reads for every new entry. Ordered by id so two rules with the same suppression key are
     * always judged in the same order — the alert that wins the key should not depend on which row the planner
     * happened to visit first.
     */
    public List<StoredRule> listEnabled() {
        return db.sql("select " + COLUMNS + " from rule where enabled = TRUE order by id").query(ROW).list();
    }

    public Optional<StoredRule> find(long id) {
        return db.sql("select " + COLUMNS + " from rule where id = ?").param(id).query(ROW).optional();
    }

    /** Required on the save path: a name is what the operator recognises in a channel hours later. */
    public boolean nameTaken(String name, long exceptId) {
        return db.sql("select 1 from rule where name = ? and id <> ? limit 1")
            .params(name, exceptId)
            .query(Integer.class)
            .optional()
            .isPresent();
    }

    public long insert(String name, String matchJson, String actionsJson, String suppressionJson,
        String quietHoursJson) {
        Instant now = Instant.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into rule (name, enabled, match, actions, suppression, quiet_hours, hit_count, created_at, "
                + "updated_at) values (?, TRUE, ?, ?, ?, ?, 0, ?, ?)")
            .params(name, matchJson, actionsJson, suppressionOrDefault(suppressionJson), quietHoursJson,
                JdbcTimes.toSql(now), JdbcTimes.toSql(now))
            .update(key, "id");
        return identity(key, name);
    }

    /**
     * Writes the editable columns and re-enables nothing: enabling is its own decision (see {@link #setEnabled}),
     * so saving an edit to a rule the circuit breaker stopped cannot silently put it back on the wire.
     */
    public void update(long id, String name, String matchJson, String actionsJson, String suppressionJson,
        String quietHoursJson) {
        int updated = db.sql("update rule set name = ?, match = ?, actions = ?, suppression = ?, quiet_hours = ?, "
                + "updated_at = ? where id = ?")
            .params(name, matchJson, actionsJson, suppressionOrDefault(suppressionJson), quietHoursJson,
                JdbcTimes.toSql(Instant.now()), id)
            .update();
        if (updated == 0) {
            throw ApiException.notFound("unknown_rule", "No rule " + id);
        }
    }

    public void delete(long id) {
        // notification_log.rule_id is ON DELETE SET NULL, so the delivery history outlives the rule that made it.
        int deleted = db.sql("delete from rule where id = ?").param(id).update();
        if (deleted == 0) {
            throw ApiException.notFound("unknown_rule", "No rule " + id);
        }
    }

    public void setEnabled(long id, boolean enabled, String reason) {
        int updated = db.sql("update rule set enabled = ?, disabled_reason = ?, updated_at = ? where id = ?")
            .params(enabled, enabled ? null : cut(reason, 512), JdbcTimes.toSql(Instant.now()), id)
            .update();
        if (updated == 0) {
            throw ApiException.notFound("unknown_rule", "No rule " + id);
        }
    }

    /**
     * FR-5.3's circuit breaker. The reason is the operator's only clue that a rule stopped by itself, and the fixed
     * prefix is what tells that apart from a rule someone switched off by hand.
     */
    public void disableByBreaker(long id, String detail) {
        db.sql("update rule set enabled = FALSE, disabled_reason = ?, updated_at = ? where id = ?")
            .params(cut(BREAKER_PREFIX + ": " + detail, 512), JdbcTimes.toSql(Instant.now()), id)
            .update();
    }

    /** How many rules stopped themselves: the health page's red badge (03 §8.2). */
    public int countAutoDisabled() {
        Long count = db.sql("select count(*) from rule where enabled = FALSE and disabled_reason like ?")
            .param(BREAKER_PREFIX + "%")
            .query(Long.class)
            .single();
        return count.intValue();
    }

    public long count() {
        return db.sql("select count(*) from rule").query(Long.class).single();
    }

    /** FR-5.1's counter for the rules list. It counts hits, not sends: suppression is a delivery decision. */
    public void bumpHits(long id, int by) {
        db.sql("update rule set hit_count = hit_count + ? where id = ?").params(by, id).update();
    }

    private static String suppressionOrDefault(String json) {
        return json == null || json.isBlank() ? DEFAULT_SUPPRESSION : json;
    }

    private static long identity(GeneratedKeyHolder key, String name) {
        Number generated = key.getKey();
        if (generated == null) {
            throw ApiException.internal("rule_not_stored", "Inserted the rule " + name + " but got no identity back");
        }
        return generated.longValue();
    }

    private static StoredRule map(ResultSet rs) throws SQLException {
        return new StoredRule(rs.getLong("id"), rs.getString("name"), rs.getBoolean("enabled"),
            rs.getString("match"), rs.getString("actions"), rs.getString("suppression"),
            rs.getString("quiet_hours"), rs.getInt("hit_count"), rs.getString("disabled_reason"),
            JdbcTimes.fromSql(rs, "created_at"), JdbcTimes.fromSql(rs, "updated_at"));
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
