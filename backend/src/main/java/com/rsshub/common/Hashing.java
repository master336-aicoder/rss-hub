package com.rsshub.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 in hex, the one hash the project uses for content comparison, raw-response addressing and token digests.
 * It lives here because three modules need it ({@code feedparse} for fallback ids, {@code feedstore} for content
 * hashes, {@code security} for tokens) and a fourth copy of the loop is how two of them quietly disagree.
 */
public final class Hashing {

    private Hashing() {
    }

    /** Never throws: SHA-256 is in the JDK's list of required algorithms. */
    public static String sha256Hex(String value) {
        return toHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    public static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
