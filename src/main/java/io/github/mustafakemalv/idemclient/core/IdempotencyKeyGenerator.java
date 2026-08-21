package io.github.mustafakemalv.idemclient.core;

/**
 * Produces a fresh idempotency key for a new logical operation.
 *
 * <p>Implementations MUST return a value that is unique per logical operation, so two distinct
 * operations never collide. Reusing the SAME key across retries of one operation is handled by the
 * library (via the Reactor Context), NOT by this generator, so a generator only ever answers
 * "give me a brand-new key".
 */
@FunctionalInterface
public interface IdempotencyKeyGenerator {

    /**
     * @return a new, unique idempotency key for one logical operation
     */
    String newKey();
}
