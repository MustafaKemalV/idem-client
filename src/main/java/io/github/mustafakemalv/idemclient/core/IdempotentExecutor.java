package io.github.mustafakemalv.idemclient.core;

import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Batteries-included entry point: runs a reactive operation with a STABLE idempotency key that is
 * preserved across retries.
 *
 * <p>The key is generated ONCE per call (a "logical operation"), placed in the Reactor Context, and
 * the retry is attached below the context write so {@code retryWhen} resubscribes the same chain
 * with the same Context. Every attempt therefore carries the SAME key. Generating the key inside
 * {@code contextWrite} would instead regenerate it on each resubscribe (a silent double-charge bug),
 * which is exactly what this class avoids.
 */
public final class IdempotentExecutor {

    private final IdempotencyKeyGenerator keyGenerator;
    private final Retry retrySpec;

    public IdempotentExecutor(IdempotencyKeyGenerator keyGenerator, Retry retrySpec) {
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
        this.retrySpec = Objects.requireNonNull(retrySpec, "retrySpec");
    }

    /**
     * Runs {@code operation} with a freshly generated idempotency key for this logical operation.
     *
     * <p>The key is generated per SUBSCRIPTION (not at assembly time), so subscribing to the returned
     * {@code Mono} more than once (a fan-out) yields a DIFFERENT key each time, while a retry of one
     * subscription keeps the SAME key.
     *
     * <p>Note: the key only reaches the wire when the idempotency exchange filter is attached to the
     * {@code WebClient}. Prefer the higher-level {@code IdempotentWebClient}, which attaches it for you.
     */
    public <T> Mono<T> execute(Mono<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return Mono.defer(() -> execute(keyGenerator.newKey(), operation));
    }

    /** Runs {@code operation} with the caller-supplied idempotency key. */
    public <T> Mono<T> execute(String idempotencyKey, Mono<T> operation) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(operation, "operation");
        return operation
                .retryWhen(retrySpec)
                .contextWrite(ctx -> IdempotencyContext.withKey(ctx, idempotencyKey));
    }
}
