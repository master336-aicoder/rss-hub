package com.rsshub.feedfetch;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the bytes go. MinIO is the production answer (03 §2), but the container is deferred (03 §14 O13), so the
 * dev default is a directory next to the H2 file. Everything under {@code rawDir} is a cache of what the
 * publisher still has: losing it costs nothing but the ability to re-parse history.
 *
 * @param rawDir directory for raw response bytes
 * @param rawRetention how long raw responses are kept before the sweep deletes them (02 §4 step 4: 30 days)
 */
@ConfigurationProperties(prefix = "rsshub.storage")
public record StorageProperties(Path rawDir, Duration rawRetention) {

    public StorageProperties {
        if (rawDir == null) {
            rawDir = Path.of("data/raw");
        }
        if (rawRetention == null || rawRetention.isNegative() || rawRetention.isZero()) {
            rawRetention = Duration.ofDays(30);
        }
    }
}
