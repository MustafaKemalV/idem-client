package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
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

    IdempotentWebClient(WebClient webClient, IdempotentExecutor executor) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.executor = Objects.requireNonNull(executor, "executor");
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
}
