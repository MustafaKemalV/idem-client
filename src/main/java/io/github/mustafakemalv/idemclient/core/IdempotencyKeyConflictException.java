package io.github.mustafakemalv.idemclient.core;

/**
 * Thrown when an idempotency key is reused with a different request fingerprint, i.e. the classic
 * "same Idempotency-Key, different body" mistake that a downstream (such as Stripe) would reject.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String key) {
        super("idempotency key '" + key + "' was already used with a different request fingerprint");
    }
}
