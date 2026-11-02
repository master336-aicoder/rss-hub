package com.rsshub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * TC-SEC-10 and TC-SEC-11, on a throwaway in-memory database with a throwaway password.
 *
 * <p>These assertions never read a real operator's credential: the only value either side of the codec is one this
 * test invented seconds earlier. What they do pin is the two properties that make a fresh install safe — an empty
 * database plus an empty password is a startup failure rather than a well-known account, and a stored value is
 * always a prefixed hash rather than whatever was typed.
 */
class OperatorBootstrapTest {

    private static final String THROWN_AWAY_PASSWORD = "a-throwaway-password-for-this-test-only";

    private final JdbcClient db = freshDatabase();
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    @DisplayName("TC-SEC-10: an empty database and no password is a refused start, not a default account")
    void refusesToOpenAnEmptySiteWithoutAPassword() {
        assertThat(count()).isZero();

        assertThatThrownBy(() -> run("", null))
            .isInstanceOf(IllegalStateException.class)
            .message().contains("rsshub.operator.password");
        assertThatThrownBy(() -> run("admin", "   "))
            .isInstanceOf(IllegalStateException.class);
        // The point of refusing: nothing may be left behind that a scripted login could then use.
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("TC-SEC-11: what is stored is a verifiable, prefixed hash and never the typed value")
    void storesAPrefixedHashNotThePassword() {
        run("admin", THROWN_AWAY_PASSWORD);

        assertThat(count()).isEqualTo(1);
        String stored = db.sql("SELECT password_hash FROM app_user WHERE username = 'admin'")
            .query(String.class)
            .single();
        assertThat(stored).isNotEqualTo(THROWN_AWAY_PASSWORD).doesNotContain(THROWN_AWAY_PASSWORD);
        // The delegating encoder's prefix is what makes a later migration to another algorithm a data problem
        // rather than a lockout; the specific algorithm is O7's choice, so only the prefix is asserted.
        assertThat(stored).startsWith("{").contains("}");
        assertThat(encoder.matches(THROWN_AWAY_PASSWORD, stored)).isTrue();
        assertThat(encoder.matches("wrong", stored)).isFalse();
    }

    @Test
    @DisplayName("a second start cannot overwrite a password someone rotated by hand")
    void rerunsLeaveAnExistingAccountAlone() {
        run("admin", THROWN_AWAY_PASSWORD);
        String first = db.sql("SELECT password_hash FROM app_user WHERE username = 'admin'")
            .query(String.class)
            .single();

        run("someone-else", "a-completely-different-password");

        assertThat(count()).isEqualTo(1);
        assertThat(db.sql("SELECT password_hash FROM app_user WHERE username = 'admin'")
            .query(String.class).single()).isEqualTo(first);
    }

    private void run(String username, String password) {
        new OperatorBootstrap(db, encoder, new OperatorProperties(username, password))
            .run(new org.springframework.boot.DefaultApplicationArguments());
    }

    private long count() {
        return db.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
    }

    /** The DDL of V1__baseline_schema.sql, so this test is failing on behaviour and not on a schema typo. */
    private static JdbcClient freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:bootstrap-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        JdbcClient client = JdbcClient.create(dataSource);
        client.sql("""
            CREATE TABLE app_user (
              id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
              username      VARCHAR(64)  NOT NULL UNIQUE,
              password_hash VARCHAR(255) NOT NULL,
              totp_secret   VARCHAR(255),
              created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """).update();
        return client;
    }
}
