package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * A {@link WebClient} paired with an {@link IdempotentExecutor}, with the idempotency filter already
 * attached, so callers cannot accidentally send a request WITHOUT the {@code Idempotency-Key} header.
 *
 * <p>This is the recommended, footgun-free entry point: obtain one from
 * {@link IdempotentWebClientFactory#create(WebClient.Builder)} and every call routed through
 * {@link #execute(Function)} both carries a stable key and is retried by the executor.
 */
public final class IdempotentWebClient {

    private final WebClient webClient;
    private final IdempotentExecutor executor;
    private final KeyFingerprintGuard guard; // nullable = fingerprint guarding off

    IdempotentWebClient(WebClient webClient, IdempotentExecutor executor, KeyFingerprintGuard guard) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.guard = guard;
    }

    /**
     * Runs the given call through the executor with a freshly generated idempotency key. The
     * {@code WebClient} handed to {@code call} already carries the idempotency filter.
     */
    public <T> Mono<T> execute(Function<WebClient, Mono<T>> call) {
        Objects.requireNonNull(call, "call");
        return executor.execute(call.apply(webClient));
    }

    /** Runs the given call with a caller-supplied idempotency key. */
    public <T> Mono<T> execute(String idempotencyKey, Function<WebClient, Mono<T>> call) {
        Objects.requireNonNull(call, "call");
        return executor.execute(idempotencyKey, call.apply(webClient));
    }

    /**
     * Runs the call with a caller-supplied key and a fingerprint of the request. If the same key was
     * already used with a DIFFERENT fingerprint (a same-key-different-body mistake), it fails with an
     * {@link io.github.mustafakemalv.idemclient.core.IdempotencyKeyConflictException} before the request
     * is sent. You compute the fingerprint (e.g. a hash of the body); the library does not buffer the
     * reactive body.
     */
    public <T> Mono<T> execute(String idempotencyKey, String fingerprint, Function<WebClient, Mono<T>> call) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(call, "call");
        return Mono.defer(() -> {
            if (guard != null) {
                guard.check(idempotencyKey, fingerprint);
            }
            return executor.execute(idempotencyKey, call.apply(webClient));
        });
    }
}
