package com.rsshub.bootstrap;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Provisions the one operator account on an empty database. Re-running is a no-op: once a user exists the
 * property is ignored, so changing it can never silently overwrite a password someone rotated by hand.
 */
@Component
public class OperatorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final OperatorProperties properties;

    public OperatorBootstrap(JdbcClient jdbc, PasswordEncoder passwordEncoder, OperatorProperties properties) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
        if (existing != null && existing > 0) {
            return;
        }
        String password = properties.password();
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("""
                No operator account exists and rsshub.operator.password is not set.
                Start once with the password you want, e.g. RSSHUB_OPERATOR_PASSWORD=... (it is only read while \
                app_user is empty; the hash is stored, the value is not).""");
        }
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES (:username, :hash)").params(Map.of(
            "username", properties.username(),
            "hash", passwordEncoder.encode(password))).update();
        log.info("provisioned operator account [{}] from configuration", properties.username());
    }
}
