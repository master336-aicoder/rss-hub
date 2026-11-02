package com.rsshub.security;

import com.rsshub.common.ApiException;
import com.rsshub.common.Hashing;
import com.rsshub.common.JdbcTimes;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@code api_token} (03 §9.1): the machine credential, as opposed to the operator's session cookie.
 *
 * <h2>What is stored</h2>
 * Only the SHA-256 of the secret. The plaintext is returned once, at creation, and never again — a database dump
 * therefore cannot be turned into a working token, and there is no "reveal" button to leak. Scope is checked by the
 * authorization rules, not by the caller remembering to.
 */
@Service
public class ApiTokenService {

    /** The three scopes 03 §3.1 allows; anything else is a typo in a request body, not a new capability. */
    public static final List<String> SCOPES = List.of("ingest", "read", "admin");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What {@code GET /api/tokens} lists: enough to recognise and revoke a credential, never enough to use one. */
    public record Token(long id, String name, String scope, Instant createdAt, Instant lastUsedAt) {
    }

    public record Created(long id, String name, String scope, String secret) {
    }

    private static final RowMapper<Token> ROW = (rs, rowNum) -> new Token(
        rs.getLong("id"), rs.getString("name"), rs.getString("scope"),
        JdbcTimes.fromSql(rs, "created_at"), JdbcTimes.fromSql(rs, "last_used_at"));

    private final JdbcClient db;

    public ApiTokenService(JdbcClient db) {
        this.db = db;
    }

    /**
     * 256 bits of entropy, base64url without padding: the table can be read by anyone who has the database, so the
     * secret has to be unguessable on its own terms rather than "hard to brute force".
     */
    public Created create(String name, String scope) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name_required", "A token needs a name so you can recognise it later");
        }
        if (!SCOPES.contains(scope)) {
            throw ApiException.badRequest("unknown_scope", "scope is one of " + String.join(", ", SCOPES));
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = Instant.now();
        db.sql("insert into api_token (name, scope, token_hash, created_at) values (?, ?, ?, ?)")
            .params(name.trim(), scope, Hashing.sha256Hex(raw), JdbcTimes.toSql(now))
            .update();
        // Read it back by the hash, which is the unique column: max(id) would be wrong the moment two tokens
        // are created concurrently, and the id is what the operator uses to revoke.
        long id = db.sql("select id from api_token where token_hash = ?")
            .param(Hashing.sha256Hex(raw))
            .query(Long.class)
            .optional()
            .orElseThrow(() -> ApiException.internal("token_lost", "Inserted a token but cannot read it back"));
        return new Created(id, name.trim(), scope, raw);
    }

    public List<Token> list() {
        return db.sql("select id, name, scope, created_at, last_used_at from api_token order by id desc")
            .query(ROW)
            .list();
    }

    /** Revocation is a timestamp, not a delete: the audit trail of "when did CI lose its key" is the only record. */
    public void revoke(long id) {
        int rows = db.sql("update api_token set revoked_at = ? where id = ? and revoked_at is null")
            .params(JdbcTimes.toSql(Instant.now()), id)
            .update();
        if (rows != 1) {
            throw ApiException.notFound("unknown_token", "No active token " + id);
        }
    }

    /**
     * One write per authenticated request, and only when the previous stamp is more than a minute old: a reader
     * polling every 30 seconds would otherwise turn every request into an UPDATE on a row that nobody reads.
     */
    public void touch(long id) {
        Instant now = Instant.now();
        db.sql("update api_token set last_used_at = ? where id = ? and (last_used_at is null or last_used_at < ?)")
            .params(JdbcTimes.toSql(now), id, JdbcTimes.toSql(now.minusSeconds(60)))
            .update();
    }

    /** The lookup the filter needs: it must be able to resolve the row from the same hash it checked the scope on. */
    public Optional<TokenRow> findActive(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) {
            return Optional.empty();
        }
        return db.sql("select id, name, scope from api_token where token_hash = ? and revoked_at is null")
            .param(Hashing.sha256Hex(rawToken))
            .query((rs, rowNum) -> new TokenRow(rs.getLong("id"), rs.getString("name"), rs.getString("scope")))
            .optional();
    }

    /** Enough to build an authentication and to name the credential in a log line — never the secret. */
    public record TokenRow(long id, String name, String scope) {
    }
}
