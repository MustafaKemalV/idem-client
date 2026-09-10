package io.github.mustafakemalv.idemclient.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Helpers for deterministic idempotency keys derived from a business identity.
 *
 * <p>A random UUID ({@link UuidIdempotencyKeyGenerator}) is stable only within one subscription. To
 * make retries safe across a process restart or an outbox/queue replay, derive the key deterministically
 * from the operation itself (for example the order id), so the SAME logical operation always maps to
 * the SAME key. Hashing keeps the key deterministic without leaking the business identity on the wire.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /**
     * Returns a deterministic key (SHA-256 hex) derived from the given parts. The same parts always
     * produce the same key; different parts (order preserved) produce different keys.
     *
     * <p>The encoding is part of the contract, not an implementation detail: each part is written as
     * its UTF-8 byte length, a colon, then its UTF-8 bytes, and the concatenation is hashed. Changing
     * it would change every key every caller has already persisted, so it is pinned by a test.
     *
     * @throws IllegalArgumentException if no parts are given
     * @throws NullPointerException if any part is null
     */
    public static String of(String... parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("at least one part is required");
        }
        MessageDigest digest = sha256();
        for (String part : parts) {
            Objects.requireNonNull(part, "part");
            byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
            // Length-prefix each part so ["a","bc"] and ["ab","c"] never collide. The prefix counts
            // BYTES, not characters: it has to describe exactly what follows it, and for a non-ASCII
            // part a character count does not, which leaves the no-collision claim unproven.
            digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(encoded);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // guaranteed by the platform
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
