package io.github.mustafakemalv.idemclient.core;

import java.util.Optional;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Carries the idempotency key inside the Reactor {@link Context} instead of a {@link ThreadLocal}.
 *
 * <p>A ThreadLocal is tied to a thread, and a reactive chain hops threads, so the key would be lost.
 * The Reactor Context is tied to the SUBSCRIPTION, so it survives thread hops and, crucially, a
 * retry: {@code retryWhen} resubscribes the same chain with the same Context, so every attempt sees
 * the same key.
 */
public final class IdempotencyContext {

    /** Unique, namespaced key under which the idempotency key is stored in the Reactor Context. */
    private static final String CONTEXT_KEY = IdempotencyContext.class.getName() + ".KEY";

    private IdempotencyContext() {
    }

    /**
     * Returns a copy of {@code context} with the idempotency key stored under our namespaced key.
     * Reactor Context is immutable, so this returns a NEW context; the caller must use the result.
     */
    public static Context withKey(Context context, String idempotencyKey) {
        return context.put(CONTEXT_KEY, idempotencyKey);
    }

    /** Reads the idempotency key from a {@link ContextView}, if present. */
    public static Optional<String> keyFrom(ContextView context) {
        return context.getOrEmpty(CONTEXT_KEY);
    }
}
