package com.rsshub.notify;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The notifier's knobs (03 §8.3). They are few on purpose: the retry ladder is a design decision written into
 * {@link DeliveryService}, not something to re-tune per install.
 *
 * @param enabled false parks every pending notification in the queue; tests drive {@link DeliveryService} directly,
 *        and a background round claiming the same rows would race their assertions
 * @param connectTimeout time to establish the socket to a channel
 * @param readTimeout time for the whole answer, including a vendor that is throttling us
 * @param secrets the signing keys, by the name a rule's {@code secretRef} uses. Values come from the environment
 *        ({@code RSSHUB_NOTIFY_SECRETS_<REF>}), never from the database: a rule row is exported with every backup and
 *        shown in the UI, and only the reference is stored with it (03 §9.1)
 * @param userAgent sent verbatim, so a receiver can tell an alert from this install from any other webhook caller
 */
@ConfigurationProperties(prefix = "rsshub.notify")
public record NotifyPolicy(boolean enabled, Duration connectTimeout, Duration readTimeout, String userAgent,
    Map<String, String> secrets) {

    public NotifyPolicy {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        userAgent = userAgent == null || userAgent.isBlank() ? "rss-hub/1.0" : userAgent;
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    }

    /**
     * Looks a {@code secretRef} up. The keys are matched case- and separator-insensitively because a ref written
     * {@code ci-hook} in the UI has no legal environment form: Spring lower-cases what it can and a hyphen cannot
     * survive a variable name at all, so the install sets {@code RSSHUB_NOTIFY_SECRETS_CI_HOOK}, which binds as the
     * key {@code ci.hook}. Stripping {@code -}, {@code _} and {@code .} from both sides makes all three spellings
     * mean the same key.
     */
    public String secret(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String wanted = normalize(ref);
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            if (normalize(entry.getKey()).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
    }
}
