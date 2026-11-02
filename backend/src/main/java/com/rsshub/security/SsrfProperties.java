package com.rsshub.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Escape hatch for the single operator who legitimately subscribes to an internal host. Empty by default:
 * adding a prefix here is a deliberate decision, and every match is logged by {@link SsrfGuard}.
 */
@ConfigurationProperties(prefix = "rsshub.ssrf")
public record SsrfProperties(List<String> allowPrivatePrefixes) {

    public SsrfProperties {
        allowPrivatePrefixes = allowPrivatePrefixes == null ? List.of() : List.copyOf(allowPrivatePrefixes);
    }
}
