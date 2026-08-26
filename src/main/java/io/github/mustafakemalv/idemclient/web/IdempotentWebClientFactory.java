package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Creates {@link IdempotentWebClient} instances from a user-supplied {@link WebClient.Builder},
 * attaching the {@link IdempotencyKeyExchangeFilter} for you so the header can never be forgotten.
 *
 * <p>Overloads let you point different providers at different filters (e.g. a custom header name) or
 * executors (e.g. a different retry policy) while keeping the footgun-free wrapper.
 */
public final class IdempotentWebClientFactory {

    private final IdempotentExecutor executor;
    private final IdempotencyKeyExchangeFilter filter;
    private final KeyFingerprintGuard guard; // nullable = fingerprint guarding off

    public IdempotentWebClientFactory(IdempotentExecutor executor, IdempotencyKeyExchangeFilter filter) {
        this(executor, filter, null);
    }

    public IdempotentWebClientFactory(IdempotentExecutor executor, IdempotencyKeyExchangeFilter filter,
            KeyFingerprintGuard guard) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.guard = guard;
    }

    /**
     * Attaches the default idempotency filter to {@code builder} and wraps the resulting WebClient
     * together with the default executor, so every call sends a stable {@code Idempotency-Key}. The
     * caller keeps full control of the builder (base URL, other filters, codecs).
     */
    public IdempotentWebClient create(WebClient.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return create(builder, this.filter, this.executor);
    }

    /** As {@link #create(WebClient.Builder)}, but with a provider-specific filter (e.g. header name). */
    public IdempotentWebClient create(WebClient.Builder builder, IdempotencyKeyExchangeFilter filter) {
        return create(builder, filter, this.executor);
    }

    /** As {@link #create(WebClient.Builder)}, but with a provider-specific filter and executor. */
    public IdempotentWebClient create(WebClient.Builder builder, IdempotencyKeyExchangeFilter filter,
            IdempotentExecutor executor) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(executor, "executor");
        return new IdempotentWebClient(builder.filter(filter).build(), executor, this.guard);
    }
}
