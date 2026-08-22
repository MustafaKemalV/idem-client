package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotencyContext;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * An {@link ExchangeFilterFunction} that stamps outbound WebClient requests with the idempotency
 * key carried in the Reactor Context.
 *
 * <p>The key is read from the Context (not a ThreadLocal), so on a retry {@code retryWhen}
 * resubscribes the chain with the same Context and this filter reads the SAME key on every attempt.
 * If no key is present, or the request already carries the header explicitly, the request passes
 * through unchanged.
 */
public final class IdempotencyKeyExchangeFilter implements ExchangeFilterFunction {

    /** The IETF-draft header name used by Stripe, PayPal, and others. */
    public static final String DEFAULT_HEADER_NAME = "Idempotency-Key";

    private final String headerName;

    public IdempotencyKeyExchangeFilter(String headerName) {
        this.headerName = headerName;
    }

    public IdempotencyKeyExchangeFilter() {
        this(DEFAULT_HEADER_NAME);
    }

    /** @return the header name this filter writes the idempotency key to. */
    public String headerName() {
        return headerName;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(context -> {
            // Respect an explicitly set header: never override what the caller put on the request.
            if (request.headers().containsHeader(headerName)) {
                return next.exchange(request);
            }
            return IdempotencyContext.keyFrom(context)
                    .map(key -> next.exchange(stampHeader(request, key)))
                    .orElseGet(() -> next.exchange(request));
        });
    }

    private ClientRequest stampHeader(ClientRequest request, String key) {
        return ClientRequest.from(request)
                .header(headerName, key)
                .build();
    }
}
