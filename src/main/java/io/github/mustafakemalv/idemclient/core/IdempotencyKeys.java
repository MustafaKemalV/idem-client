package io.github.mustafakemalv.idemclient.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Helpers for deterministic idempotency keys derived from a business identity.
 *
 * <p>A random UUID ({@link UuidIdempotencyKeyGenerator}) is stable only within one subscription. To
 * make retries safe across a process restart or an outbox/queue replay, derive the key deterministically
 * from the operation itself (for example the order id), so the SAME logical operation always maps to
 * the SAME key.
 *
 * <p><b>What hashing does and does not do here.</b> {@link #of} is a plain SHA-256 over a documented
 * encoding, so the key does not look like your data, but it does not hide it either. Hashing conceals
 * an input only when the input is hard to guess, and a business identity is not: an order id, an email
 * address or a card number comes from a small, enumerable space, so anyone holding the key can try
 * candidates until one matches. Treat {@link #of} as a way to get a stable, well-formed key, not as a
 * way to keep a value secret, and do not feed it a card number. Where the key must also be
 * unguessable, use {@link #hmac} with a secret.
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
     * <p>The result is reversible for a low-entropy input; see the class javadoc.
     *
     * @throws IllegalArgumentException if no parts are given
     * @throws NullPointerException if any part is null
     */
    public static String of(String... parts) {
        return toHex(sha256().digest(framed(parts)));
    }

    /**
     * Returns a deterministic key (HMAC-SHA256 hex) derived from the given parts and a secret, over the
     * same encoding as {@link #of}. Same parts and same secret always produce the same key, and without
     * the secret the key can be neither reversed nor predicted.
     *
     * <p><b>The secret must outlive the operation, and must be identical everywhere the operation can
     * be replayed.</b> This is not a detail. The whole point of a deterministic key is that a replay
     * after a crash, a redeploy or a failover produces the SAME key, so the downstream recognises it.
     * Rotate or lose the secret and every key changes, a replay then looks like a brand-new operation,
     * and the double charge this library exists to prevent walks straight in through the feature meant
     * to prevent it. Store it as you would a signing key, roll it only with a migration you have thought
     * through, and never derive it per instance.
     *
     * @param secret the HMAC key; the caller owns it, the library neither stores it nor logs it
     * @throws IllegalArgumentException if the secret is empty or no parts are given
     * @throws NullPointerException if the secret or any part is null
     */
    public static String hmac(byte[] secret, String... parts) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length == 0) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        byte[] framed = framed(parts);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return toHex(mac.doFinal(framed));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e); // guaranteed by the platform
        }
    }

    /**
     * Encodes the parts unambiguously: each part as its UTF-8 byte length, a colon, then its UTF-8
     * bytes. The length prefix counts BYTES, not characters, because it has to describe exactly what
     * follows it; a character count would not, for a non-ASCII part, and the argument that ["a","bc"]
     * cannot collide with ["ab","c"] rests entirely on that framing being accurate.
     */
    private static byte[] framed(String... parts) {
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("at least one part is required");
        }
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        for (String part : parts) {
            Objects.requireNonNull(part, "part");
            byte[] encoded = part.getBytes(StandardCharsets.UTF_8);
            framed.writeBytes(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
            framed.write(':');
            framed.writeBytes(encoded);
        }
        return framed.toByteArray();
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
