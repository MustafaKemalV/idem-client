package io.github.mustafakemalv.idemclient.autoconfigure;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Auto-configures idem-client: a default UUID {@link IdempotencyKeyGenerator}, an
 * {@link IdempotentExecutor} whose retry policy is built from {@code idem-client.*} properties, and an
 * {@link IdempotencyKeyExchangeFilter} bean to add to your WebClient. Backs off entirely when
 * {@code idem-client.enabled=false}; every bean is {@link ConditionalOnMissingBean} so any of them
 * can be overridden.
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(prefix = "idem-client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdemClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyKeyGenerator idempotencyKeyGenerator() {
        return new UuidIdempotencyKeyGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentExecutor idempotentExecutor(IdempotencyKeyGenerator keyGenerator, IdempotencyProperties properties) {
        validate(properties);
        Retry retrySpec = Retry.backoff(properties.getMaxAttempts(), properties.getMinBackoff())
                .maxBackoff(properties.getMaxBackoff())
                .filter(IdemClientAutoConfiguration::isRetryable);
        return new IdempotentExecutor(keyGenerator, retrySpec);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyKeyExchangeFilter idempotencyKeyExchangeFilter(IdempotencyProperties properties) {
        return new IdempotencyKeyExchangeFilter(properties.getHeaderName());
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentWebClientFactory idempotentWebClientFactory(
            IdempotentExecutor executor, IdempotencyKeyExchangeFilter filter) {
        return new IdempotentWebClientFactory(executor, filter);
    }

    /**
     * Retries transient failures only: HTTP 5xx and 429 (Too Many Requests), plus non-HTTP-response
     * errors (typically transport failures like a connection reset or timeout, exactly the ambiguous
     * "did my request arrive?" case a stable idempotency key protects). Deterministic HTTP 4xx
     * (400/401/403/404/422, ...) are NOT retried, since a retry cannot fix them.
     */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            return status.is5xxServerError() || status.value() == 429;
        }
        return true;
    }

    private static void validate(IdempotencyProperties properties) {
        if (properties.getMaxAttempts() < 0) {
            throw new IllegalStateException("idem-client.max-attempts must be >= 0");
        }
        if (properties.getMinBackoff().isNegative() || properties.getMaxBackoff().isNegative()) {
            throw new IllegalStateException("idem-client.min-backoff and max-backoff must not be negative");
        }
        if (properties.getMaxBackoff().compareTo(properties.getMinBackoff()) < 0) {
            throw new IllegalStateException("idem-client.max-backoff must be >= min-backoff");
        }
    }
}
