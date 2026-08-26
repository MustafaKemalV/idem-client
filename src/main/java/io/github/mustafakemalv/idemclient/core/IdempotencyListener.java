package io.github.mustafakemalv.idemclient.core;

/**
 * Observability hook for idempotent operations. Implement it (as a Spring bean) to feed metrics,
 * logging, or tracing systems; the auto-configuration installs a {@link #NOOP no-op default} when the
 * application defines none. The library itself pulls in no observability dependency.
 */
public interface IdempotencyListener {

    /** Called before each retry attempt ({@code attempt} is 1-based: the first retry is attempt 1). */
    default void onRetry(long attempt) {
    }

    /** Called when all retries are exhausted and the original error is about to propagate. */
    default void onExhausted() {
    }

    /** A listener that does nothing. */
    IdempotencyListener NOOP = new IdempotencyListener() {
    };
}
