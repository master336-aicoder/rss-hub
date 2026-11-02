package com.rsshub.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs the operator's own machine has to set. {@code secretKey} deliberately has no default: a TOTP secret
 * encrypted under a hardcoded key is plaintext with extra steps, so the feature stays off until the key exists.
 *
 * @param secretKey base64 of 32 random bytes ({@code RSSHUB_SECRET_KEY}); only needed to enrol TOTP
 */
@ConfigurationProperties(prefix = "rsshub.security")
public record SecurityProperties(String secretKey) {

    public boolean hasEncryptionKey() {
        return secretKey != null && !secretKey.isBlank();
    }
}
