package com.rsshub.feedrender;

import com.rsshub.common.Hashing;
import org.springframework.stereotype.Component;

/**
 * The content hash behind 03 §6.4: {@code W/"<sha256(fingerprint)[0..16]>"}.
 *
 * <p>Weak validators are the correct choice, not laziness. A strong ETag promises byte-for-byte identity, and these
 * documents are built on more than one machine (dev H2, prod Postgres, a rescaled item limit) where a whitespace or
 * key-order difference would make a identical-content cache miss look like a changed feed. Weak says "semantically
 * the same", which is exactly what the fingerprint measures.
 *
 * <p>The seed carries the outlet path and the format so the same content published as RSS and as Atom cannot share a
 * validator; a proxy that confuses the two would serve XML to a JSON Feed client.
 */
@Component
public class EtagService {

    private static final int TAG_LENGTH = 16;

    public String forOutlet(FeedKey key, FeedFormat format, FeedFingerprint fingerprint) {
        String seed = key.cacheKey() + '|' + format.pathToken() + '|'
            + (fingerprint.maxUpdatedAt() == null ? "-" : fingerprint.maxUpdatedAt().toString()) + '|'
            + fingerprint.itemCount() + '|'
            + (fingerprint.idDigest() == null ? "" : fingerprint.idDigest());
        return "W/\"" + Hashing.sha256Hex(seed).substring(0, TAG_LENGTH) + "\"";
    }

    /**
     * Whether a client's {@code If-None-Match} covers this validator. Weak comparison per RFC 9110 §8.8.3.2: the
     * {@code W/} prefix is ignored for a conditional GET, and the header may list several candidates or {@code *}.
     */
    public boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String ours = opaque(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String theirs = opaque(candidate);
            if ("*".equals(theirs) || theirs.equals(ours)) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String value) {
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "W/", 0, 2)) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
