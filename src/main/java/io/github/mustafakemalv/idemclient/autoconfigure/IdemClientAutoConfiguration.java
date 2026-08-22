package io.github.mustafakemalv.idemclient.autoconfigure;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
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
        Retry retrySpec = Retry.backoff(properties.getMaxAttempts(), properties.getMinBackoff());
        return new IdempotentExecutor(keyGenerator, retrySpec);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyKeyExchangeFilter idempotencyKeyExchangeFilter(IdempotencyProperties properties) {
        return new IdempotencyKeyExchangeFilter(properties.getHeaderName());
    }
}
