package com.rsshub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single operator account. There is deliberately no default password: {@link OperatorBootstrap} refuses to
 * start against an empty database without one, so a fresh install can never be reached with a well-known credential.
 */
@ConfigurationProperties(prefix = "rsshub.operator")
public record OperatorProperties(String username, String password) {

    public OperatorProperties {
        if (username == null || username.isBlank()) {
            username = "admin";
        }
    }
}
