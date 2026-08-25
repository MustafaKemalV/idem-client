package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Creates {@link IdempotentWebClient} instances from a user-supplied {@link WebClient.Builder},
 * attaching the {@link IdempotencyKeyExchangeFilter} for you so the header can never be forgotten.
 */
public final class IdempotentWebClientFactory {

    private final IdempotentExecutor executor;
    private final IdempotencyKeyExchangeFilter filter;

    public IdempotentWebClientFactory(IdempotentExecutor executor, IdempotencyKeyExchangeFilter filter) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    /**
     * Attaches the idempotency filter to {@code builder} and wraps the resulting WebClient together
     * with the executor, so every call made through the returned {@link IdempotentWebClient} sends a
     * stable {@code Idempotency-Key}. The caller keeps full control of the builder (base URL, other
     * filters, codecs); this only adds the idempotency filter on top.
     */
    public IdempotentWebClient create(WebClient.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return new IdempotentWebClient(builder.filter(filter).build(), executor);
    }
}
