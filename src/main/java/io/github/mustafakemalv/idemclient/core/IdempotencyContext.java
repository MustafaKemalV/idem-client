package io.github.mustafakemalv.idemclient.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Namespaced key for the scope marker described on {@link #recordStampedRequest}. */
    private static final String SCOPE_KEY = IdempotencyContext.class.getName() + ".SCOPE";

    private IdempotencyContext() {
    }

    /**
     * Returns a copy of {@code context} with the idempotency key stored under our namespaced key.
     * Reactor Context is immutable, so this returns a NEW context; the caller must use the result.
     */
    public static Context withKey(Context context, String idempotencyKey) {
        return context.put(CONTEXT_KEY, idempotencyKey)
                .put(SCOPE_KEY, new AtomicReference<String>());
    }

    /** Reads the idempotency key from a {@link ContextView}, if present. */
    public static Optional<String> keyFrom(ContextView context) {
        return context.getOrEmpty(CONTEXT_KEY);
    }

    /**
     * Records that the key in this Context was stamped on {@code requestIdentity}, and reports whether
     * a DIFFERENT request was already stamped under the same key.
     *
     * <p>The library's golden rule is that a key identifies one logical operation: a retry is the same
     * key, a new operation is a new key. Nothing stops a caller from making two different calls inside
     * one {@code execute(...)}, though, and both would then be stamped with one key. The downstream
     * would treat the second as a replay of the first and could return the first response for it, so
     * the second operation silently never happens.
     *
     * <p>The marker is a mutable holder inside an otherwise immutable Context. That is deliberate and
     * safe here: {@link #withKey} runs once per subscription, so each logical operation gets its own
     * holder, and a retry reuses the same Context and therefore records the same identity again.
     *
     * @return the identity of the first request stamped under this key, when it differs from
     *     {@code requestIdentity}; empty when this is the first request, a retry of it, or when the
     *     Context was not written by this library
     */
    public static Optional<String> recordStampedRequest(ContextView context, String requestIdentity) {
        AtomicReference<String> firstStamped = context.getOrDefault(SCOPE_KEY, null);
        if (firstStamped == null || firstStamped.compareAndSet(null, requestIdentity)) {
            return Optional.empty();
        }
        String first = firstStamped.get();
        return first.equals(requestIdentity) ? Optional.empty() : Optional.of(first);
    }
}
