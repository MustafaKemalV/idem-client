package io.github.mustafakemalv.idemclient.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>The key is also validated here, once per logical operation and BEFORE the first attempt is
 * dispatched, rather than per attempt on the way out. A key that cannot be sent is a caller mistake
 * that no retry can fix, so it must fail immediately and only once.
 */
public final class IdempotentExecutor {

    /**
     * Longest accepted idempotency key. Providers cap the header (Stripe rejects a key over 255
     * characters) and an oversized header block can also be refused outright by a proxy.
     */
    public static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyKeyGenerator keyGenerator;
    private final Retry retrySpec;
    private final Duration perAttemptTimeout;
    private final IdempotencyListener listener;

    public IdempotentExecutor(IdempotencyKeyGenerator keyGenerator, Retry retrySpec) {
        this(keyGenerator, retrySpec, null, IdempotencyListener.NOOP);
    }

    /**
     * @param perAttemptTimeout bounds each individual attempt; a timed-out attempt becomes a retryable
     *     error (retried safely under the stable key). {@code null} means no timeout.
     */
    public IdempotentExecutor(IdempotencyKeyGenerator keyGenerator, Retry retrySpec, Duration perAttemptTimeout) {
        this(keyGenerator, retrySpec, perAttemptTimeout, IdempotencyListener.NOOP);
    }

    /**
     * @param listener notified of the key, of each retry, and of a final failure. The executor owns
     *     these callbacks rather than the {@link Retry} spec, because only the executor knows the key,
     *     and a listener wired into the retry spec would report an attempt count with nothing to
     *     attach it to.
     */
    public IdempotentExecutor(IdempotencyKeyGenerator keyGenerator, Retry retrySpec, Duration perAttemptTimeout,
            IdempotencyListener listener) {
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
        this.retrySpec = Objects.requireNonNull(retrySpec, "retrySpec");
        this.perAttemptTimeout = perAttemptTimeout;
        this.listener = Objects.requireNonNull(listener, "listener");
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
     *
     * <p>Because the key is minted inside the returned {@code Mono}, a key that violates the contract
     * of {@link IdempotencyKeyGenerator} surfaces as an {@code onError} signal, not as a thrown
     * exception.
     */
    public <T> Mono<T> execute(Mono<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return Mono.defer(() -> {
            String key = keyGenerator.newKey();
            validateKey(key);
            listener.onKeyMinted(key);
            return execute(key, operation);
        });
    }

    /**
     * Runs {@code operation} with the caller-supplied idempotency key.
     *
     * @throws IllegalArgumentException if the key is blank, longer than {@link #MAX_KEY_LENGTH}, or
     *     contains anything but printable US-ASCII. Validation happens here, above the retry, so an
     *     unusable key fails once and before any request is dispatched.
     */
    public <T> Mono<T> execute(String idempotencyKey, Mono<T> operation) {
        validateKey(idempotencyKey);
        Objects.requireNonNull(operation, "operation");
        Mono<T> attempt = (perAttemptTimeout != null) ? operation.timeout(perAttemptTimeout) : operation;
        return Mono.defer(() -> {
            // One counter per SUBSCRIPTION, so a fan-out counts its attempts separately.
            AtomicLong attempts = new AtomicLong();
            return attempt
                    .doOnSubscribe(subscription -> {
                        long attempt_ = attempts.incrementAndGet();
                        if (attempt_ > 1) {
                            listener.onRetry(idempotencyKey, attempt_ - 1);
                        }
                    })
                    .retryWhen(retrySpec)
                    .doOnError(error -> listener.onFailed(idempotencyKey, attempts.get(), error))
                    .contextWrite(ctx -> IdempotencyContext.withKey(ctx, idempotencyKey));
        });
    }

    /**
     * Enforces the key contract: present, non-blank, at most {@link #MAX_KEY_LENGTH} characters, and
     * printable US-ASCII only (0x21-0x7E).
     *
     * <p>Blank is rejected on its own, ahead of the character check, because it is the dangerous case:
     * a blank key is a perfectly legal HTTP header value, so it would be sent as an empty
     * {@code Idempotency-Key}, read by the downstream as no key at all, and leave the caller with zero
     * protection and no error to notice. Space is outside the printable range anyway; the separate
     * check exists to say why.
     */
    static String validateKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not be blank: a blank key is sent as an empty header, "
                            + "which the downstream reads as no key at all, so there is no protection");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be at most " + MAX_KEY_LENGTH
                    + " characters, was " + idempotencyKey.length());
        }
        for (int i = 0; i < idempotencyKey.length(); i++) {
            char c = idempotencyKey.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException(
                        "idempotencyKey must contain printable US-ASCII only (0x21-0x7E), "
                                + "found 0x" + Integer.toHexString(c) + " at index " + i);
            }
        }
        return idempotencyKey;
    }
}
