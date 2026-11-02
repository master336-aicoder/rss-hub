package com.rsshub.security;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * One row in {@code app_user}, read straight from the table — a single-operator install does not earn a user
 * management subsystem. Password comparison is left to the configured {@code PasswordEncoder}.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    /** The same row plus the encrypted second factor, which {@link UserDetailsService} has no vocabulary for. */
    private static final String SELECT_ACCOUNT =
        "SELECT id, username, password_hash, totp_secret FROM app_user WHERE username = ?";

    private static final RowMapper<StoredOperator> OPERATOR_ROW = (rs, rowNum) -> new StoredOperator(
        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("totp_secret"));

    private final JdbcClient jdbc;

    public DbUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        StoredOperator operator = find(username).orElseThrow(() -> new UsernameNotFoundException("no such operator"));

        return User.builder()
            .username(operator.username())
            .password(operator.passwordHash())
            .roles("OPERATOR")
            .build();
    }

    /** Empty rather than an exception: a login for an unknown name must look exactly like a wrong password. */
    public Optional<StoredOperator> find(String username) {
        return jdbc.sql(SELECT_ACCOUNT)
            .param(username)
            .query(OPERATOR_ROW)
            .optional();
    }

    /**
     * Enrolment and removal both write here. Null clears it — an operator who loses their phone has to be able to
     * turn the second factor off with a password, or the only way back in is editing the database by hand.
     */
    public void storeTotpSecret(long userId, String encryptedSecret) {
        jdbc.sql("update app_user set totp_secret = ? where id = ?").params(encryptedSecret, userId).update();
    }

    record StoredOperator(long id, String username, String passwordHash, String totpSecret) {}
}
