package io.github.mustafakemalv.idemclient.core;

/**
 * Thrown when an idempotency key is reused with a different request fingerprint, i.e. the classic
 * "same Idempotency-Key, different body" mistake that a downstream (such as Stripe) would reject.
 *
 * <p>The message carries only a prefix of the key. Exception messages travel further than the code
 * that raised them, into logs, error responses and monitoring systems, and a key may be derived from
 * a business identifier or be treated by a downstream as a token for retrieving a stored response.
 * The filter already truncates the key in its DEBUG line for the same reason; the full value is
 * available to code that genuinely needs it, from {@link #idempotencyKey()}.
 *
 * @since 0.1.0
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("idempotency key '" + truncate(idempotencyKey) + "' was already used with a different request fingerprint");
        this.idempotencyKey = idempotencyKey;
    }

    /** The key in full, for a caller that needs to act on it rather than log it. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    private static String truncate(String key) {
        if (key == null) {
            return "null";
        }
        return key.length() <= 8 ? key : key.substring(0, 8) + "...";
    }
}
