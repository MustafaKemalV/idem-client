package io.github.mustafakemalv.idemclient.core;

/**
 * Observability hook for idempotent operations. Implement it (as a Spring bean) to feed metrics,
 * logging, or tracing systems; the auto-configuration installs a {@link #NOOP no-op default} when the
 * application defines none. The library itself pulls in no observability dependency.
 *
 * <p>Every callback carries the idempotency key, and that is the point of the hook rather than a
 * detail of it. When retries are exhausted the operation has not simply failed, it has ended in an
 * UNKNOWN state: the request was dispatched, the downstream may have processed it, and the outcome
 * never came back. The only way out of that state is to reconcile with the downstream, and to
 * reconcile you need the key that was actually sent. A generated key exists only inside the
 * subscription, so without these callbacks it can never be recovered.
 */
public interface IdempotencyListener {

    /**
     * Called once per logical operation, when the library mints a key for a new subscription.
     *
     * <p>Log this. Two things depend on it. First, it is the anchor for reconciliation: it is the only
     * place a generated key becomes visible outside the subscription. Second, seeing this fire more
     * than once for what you believe is a single logical operation is the signature of a retry stacked
     * ABOVE {@code execute(...)}, which mints a fresh key per attempt and defeats the whole mechanism.
     */
    default void onKeyMinted(String idempotencyKey) {
    }

    /** Called before each retry attempt ({@code attempt} is 1-based: the first retry is attempt 1). */
    default void onRetry(String idempotencyKey, long attempt) {
    }

    /**
     * Called when the operation fails for good, after any retries.
     *
     * @param attempts how many attempts were made in total. More than one means the request reached
     *     the wire more than once and the outcome is ambiguous: reconcile on {@code idempotencyKey}
     *     rather than assuming nothing happened.
     */
    default void onFailed(String idempotencyKey, long attempts, Throwable error) {
    }

    /** A listener that does nothing. */
    IdempotencyListener NOOP = new IdempotencyListener() {
    };
}
