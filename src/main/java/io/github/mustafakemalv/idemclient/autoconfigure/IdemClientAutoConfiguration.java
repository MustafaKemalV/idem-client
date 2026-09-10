package io.github.mustafakemalv.idemclient.autoconfigure;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.core.IdempotencyListener;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Auto-configures idem-client: a default UUID {@link IdempotencyKeyGenerator}, a no-op
 * {@link IdempotencyListener}, an {@link IdempotentExecutor} whose retry policy is built from
 * {@code idem-client.*} properties, an {@link IdempotencyKeyExchangeFilter} bean, and an
 * {@link IdempotentWebClientFactory}. Backs off entirely when {@code idem-client.enabled=false};
 * every bean is {@link ConditionalOnMissingBean} so any of them can be overridden.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(prefix = "idem-client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdemClientAutoConfiguration {

    /**
     * The 4xx codes the HTTP specifications designate as retryable, as opposed to the deterministic
     * ones: 408 Request Timeout (RFC 9110, "the client MAY repeat the request without modifications"),
     * 421 Misdirected Request (retry over a different connection), 425 Too Early (retry once the
     * handshake completes) and 429 Too Many Requests.
     *
     * <p>408 is the one that matters most here. The server gave up while RECEIVING the request, so it
     * may have begun processing what it did receive: the outcome is unknown, which is the case a stable
     * idempotency key exists to make safe.
     */
    private static final Set<Integer> RETRYABLE_CLIENT_ERRORS = Set.of(408, 421, 425, 429);

    @Bean
    @ConditionalOnMissingBean
    IdempotencyKeyGenerator idempotencyKeyGenerator() {
        return new UuidIdempotencyKeyGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyListener idempotencyListener() {
        return IdempotencyListener.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentExecutor idempotentExecutor(IdempotencyKeyGenerator keyGenerator, IdempotencyProperties properties,
            IdempotencyListener listener) {
        Retry retrySpec = Retry.backoff(properties.getMaxAttempts(), properties.getMinBackoff())
                .maxBackoff(properties.getMaxBackoff())
                .filter(IdemClientAutoConfiguration::isRetryable)
                // Unwrap: the caller gets the original failure, not a RetryExhaustedException wrapping
                // it. The listener is notified by the executor, which is the only thing that knows the
                // key the attempts were made under.
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
        return new IdempotentExecutor(keyGenerator, retrySpec, properties.getPerAttemptTimeout(), listener);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyKeyExchangeFilter idempotencyKeyExchangeFilter(IdempotencyProperties properties) {
        return new IdempotencyKeyExchangeFilter(properties.getHeaderName());
    }

    @Bean
    @ConditionalOnMissingBean
    KeyFingerprintGuard keyFingerprintGuard() {
        return new KeyFingerprintGuard(10_000);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentWebClientFactory idempotentWebClientFactory(
            IdempotentExecutor executor, IdempotencyKeyExchangeFilter filter, KeyFingerprintGuard guard) {
        return new IdempotentWebClientFactory(executor, filter, guard);
    }

    /**
     * Retries TRANSIENT failures only, as an ALLOW-list: HTTP 5xx, the four 4xx codes the specs call
     * retryable ({@link #RETRYABLE_CLIENT_ERRORS}), plus the transport failures
     * that leave the outcome genuinely unknown (a connection reset, a premature close, a DNS or TLS
     * failure, a per-attempt timeout). Everything else is not retried.
     *
     * <p>An allow-list, not a deny-list, and the difference is the whole point. Listing only what is
     * NOT retryable sweeps in every failure raised on the caller's own side of the exchange: a
     * {@code NullPointerException} in the caller's mapping function, a decoding failure, a 4xx the
     * caller mapped to a domain exception with {@code onStatus}. Those are deterministic, no retry can
     * fix them, and re-sending the request turns a local bug into repeated remote side effects.
     *
     * <p>The trade-off, stated plainly: map a 5xx to your own exception type with {@code onStatus} and
     * this predicate no longer recognises it either, because it can no longer see a status. Compose
     * rather than replace: {@code IdemClientAutoConfiguration.isRetryable(t) || myPredicate.test(t)}.
     */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.is5xxServerError() || RETRYABLE_CLIENT_ERRORS.contains(status.value())) {
                return true;
            }
            if (status.is4xxClientError()) {
                return false; // a status line arrived, so the outcome is known: retrying cannot help
            }
            // A status arrived but the transport died before the body did. Spring reports that as a
            // WebClientResponseException carrying the RESPONSE status (a 200, typically) with the
            // IOException as its cause. The request was dispatched, the downstream may well have
            // processed it, and we never learned the outcome. That is the ambiguous case a stable
            // idempotency key exists to make safe, and it is the one this library must retry.
            return response.getCause() instanceof IOException;
        }
        return error instanceof WebClientRequestException // reactor-netty wraps transport failures here
                || error instanceof IOException           // other connectors, and ClosedChannelException
                || error instanceof TimeoutException;     // per-attempt timeout: the outcome is unknown
    }

}
