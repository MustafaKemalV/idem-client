package io.github.mustafakemalv.idemclient.core;

/**
 * Produces a fresh idempotency key for a new logical operation.
 *
 * <p>Implementations MUST return a value that is unique per logical operation, so two distinct
 * operations never collide. Reusing the SAME key across retries of one operation is handled by the
 * library (via the Reactor Context), NOT by this generator, so a generator only ever answers
 * "give me a brand-new key".
 *
 * <p>The returned key must also be sendable as an HTTP header value: non-blank, at most
 * {@link IdempotentExecutor#MAX_KEY_LENGTH} characters, and printable US-ASCII only (0x21-0x7E).
 * {@link IdempotentExecutor} enforces this on every key it uses, so a generator that breaks the
 * contract fails the operation instead of quietly sending an unusable header.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface IdempotencyKeyGenerator {

    /**
     * @return a new, unique idempotency key for one logical operation
     */
    String newKey();
}
