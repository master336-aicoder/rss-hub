package com.rsshub.security;

import com.rsshub.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The encryption 03 §9.1 promises for {@code app_user.totp_secret} ("加密存储"): AES-256-GCM, random 12-byte IV per
 * value, keyed by {@code RSSHUB_SECRET_KEY}.
 *
 * <p>GCM rather than CBC because the ciphertext also has to be *trusted* — a database an attacker can write to can
 * otherwise shuffle or splice CBC blocks, and a mutated secret is a second factor that decrypts into something the
 * owner never enrolled. The {@code v1:} prefix leaves room to rotate the format without a migration.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityProperties props;
    private final byte[] key;

    public SecretCipher(SecurityProperties props) {
        this.props = props;
        this.key = props.hasEncryptionKey() ? decodeKey(props.secretKey()) : null;
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        require();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw ApiException.internal("encrypt_failed", "Cannot encrypt the secret: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Returns null for a value that was never enrolled. A stored value that does not decrypt is *not* silently
     * treated as "no second factor" — that would turn a corrupted row into an open door, so it throws instead and
     * the operator sees a 500 they have to fix.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        require();
        if (!stored.startsWith(PREFIX)) {
            throw ApiException.internal("secret_format",
                "A stored secret is not in " + PREFIX + " format; refusing to guess what it is");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The detail stays in the log: the message an operator needs is "the key changed", not a stack trace
            // that could quote ciphertext.
            throw ApiException.internal("secret_unreadable",
                "Cannot decrypt a stored secret — is RSSHUB_SECRET_KEY the same value as when it was enrolled?");
        }
    }

    private void require() {
        if (key == null) {
            throw ApiException.badRequest("secret_key_missing",
                "Set RSSHUB_SECRET_KEY (base64 of 32 random bytes) before storing a TOTP secret");
        }
    }

    private static byte[] decodeKey(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RSSHUB_SECRET_KEY must be base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("RSSHUB_SECRET_KEY must decode to 32 bytes, got " + decoded.length);
        }
        return decoded;
    }
}
