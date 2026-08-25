package io.github.mustafakemalv.idemclient.web;

import io.github.mustafakemalv.idemclient.core.IdempotencyContext;
import java.util.Objects;
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
 * through unchanged. A key containing control characters (CR, LF, ...) is rejected before the request
 * is sent, so the library does not rely on the transport to reject a header-injection attempt.
 */
public final class IdempotencyKeyExchangeFilter implements ExchangeFilterFunction {

    /** The IETF-draft header name used by Stripe, PayPal, and others. */
    public static final String DEFAULT_HEADER_NAME = "Idempotency-Key";

    private final String headerName;

    public IdempotencyKeyExchangeFilter(String headerName) {
        this.headerName = validateHeaderName(headerName);
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
                    .map(key -> exchangeWithKey(request, key, next))
                    .orElseGet(() -> next.exchange(request));
        });
    }

    private Mono<ClientResponse> exchangeWithKey(ClientRequest request, String key, ExchangeFunction next) {
        if (!isValidFieldValue(key)) {
            return Mono.error(new IllegalArgumentException(
                    "idempotency key contains illegal characters (CR, LF, or a control character)"));
        }
        return next.exchange(stampHeader(request, key));
    }

    private ClientRequest stampHeader(ClientRequest request, String key) {
        return ClientRequest.from(request)
                .header(headerName, key)
                .build();
    }

    private static String validateHeaderName(String headerName) {
        Objects.requireNonNull(headerName, "headerName");
        if (headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        for (int i = 0; i < headerName.length(); i++) {
            char c = headerName.charAt(i);
            if (c <= 0x20 || c == 0x7f) { // a header name has no whitespace or control characters
                throw new IllegalArgumentException("headerName contains illegal characters");
            }
        }
        return headerName;
    }

    private static boolean isValidFieldValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
