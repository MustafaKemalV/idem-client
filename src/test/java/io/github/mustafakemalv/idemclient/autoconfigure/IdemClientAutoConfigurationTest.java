package io.github.mustafakemalv.idemclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class IdemClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdemClientAutoConfiguration.class));

    @Test
    void providesDefaultBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IdempotencyKeyGenerator.class);
            assertThat(context).hasSingleBean(IdempotentExecutor.class);
            assertThat(context).hasSingleBean(IdempotencyKeyExchangeFilter.class);
        });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("idem-client.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotentExecutor.class);
            assertThat(context).doesNotHaveBean(IdempotencyKeyExchangeFilter.class);
        });
    }

    @Test
    void honorsCustomHeaderName() {
        runner.withPropertyValues("idem-client.header-name=X-My-Idempotency-Key").run(context -> {
            assertThat(context.getBean(IdempotencyKeyExchangeFilter.class).headerName())
                    .isEqualTo("X-My-Idempotency-Key");
        });
    }

    @Test
    void userDefinedGeneratorOverridesDefault() {
        runner.withUserConfiguration(CustomGeneratorConfig.class).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyKeyGenerator.class);
            assertThat(context.getBean(IdempotencyKeyGenerator.class).newKey()).isEqualTo("fixed-key");
        });
    }

    @Test
    void failsFastOnInvalidConfig() {
        runner.withPropertyValues("idem-client.max-attempts=-1").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void retryPredicateRetriesTransportErrors() {
        assertThat(IdemClientAutoConfiguration.isRetryable(new RuntimeException("connection reset"))).isTrue();
    }

    @Configuration
    static class CustomGeneratorConfig {
        @Bean
        IdempotencyKeyGenerator customGenerator() {
            return () -> "fixed-key";
        }
    }
}
