package io.github.mustafakemalv.idemclient.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyConflictException;
import io.github.mustafakemalv.idemclient.core.IdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.core.IdempotencyListener;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

class IdemClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdemClientAutoConfiguration.class));

    @Test
    void providesDefaultBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IdempotencyKeyGenerator.class);
            assertThat(context).hasSingleBean(IdempotentExecutor.class);
            assertThat(context).hasSingleBean(IdempotencyKeyExchangeFilter.class);
            assertThat(context).hasSingleBean(IdempotentWebClientFactory.class);
            assertThat(context).hasSingleBean(IdempotencyListener.class);
            assertThat(context).hasSingleBean(KeyFingerprintGuard.class);
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
    void configurationMetadataIsActuallyGenerated() throws Exception {
        // The configuration processor was declared for 24 commits and never ran: Java 23 stopped
        // enabling classpath-discovered processors implicitly, so it produced nothing and idem-client.*
        // had no IDE completion. A silent no-op is exactly why this needs an assertion and not a
        // pom entry taken on trust.
        var metadata = getClass().getResource("/META-INF/spring-configuration-metadata.json");
        assertThat(metadata).as("annotation processing must be enabled in the compiler plugin").isNotNull();
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(metadata.toURI())))
                .contains("idem-client.header-name")
                .contains("idem-client.per-attempt-timeout");
    }

    @Test
    void validatesPropertiesEvenWhenTheExecutorBeanIsReplaced() {
        // Validation used to live inside the executor bean method, so an application that supplied its
        // own executor silently skipped it and ran on nonsense configuration.
        runner.withUserConfiguration(CustomExecutorConfig.class)
                .withPropertyValues("idem-client.max-attempts=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsANonPositivePerAttemptTimeout() {
        runner.withPropertyValues("idem-client.per-attempt-timeout=0s").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void retriesTransportFailures() {
        // A real connection reset arrives wrapped like this; reactor-netty's premature close, DNS and
        // TLS failures all take the same route.
        assertThat(retryable(new WebClientRequestException(
                new SocketException("connection reset"), HttpMethod.POST,
                URI.create("http://downstream/charge"), HttpHeaders.EMPTY))).isTrue();
        assertThat(retryable(new IOException("broken pipe"))).isTrue();
        assertThat(retryable(new ClosedChannelException())).isTrue();
        // The per-attempt timeout surfaces raw, so it must be listed explicitly or the feature dies.
        assertThat(retryable(new TimeoutException("per-attempt timeout"))).isTrue();
    }

    @Test
    void retriesServerErrors() {
        assertThat(retryable(response(500))).isTrue();
        assertThat(retryable(response(503))).isTrue();
    }

    @Test
    void retriesTheClientErrorsTheSpecsCallRetryable() {
        assertThat(retryable(response(429))).isTrue(); // Too Many Requests
        assertThat(retryable(response(408))).isTrue(); // Request Timeout: the server gave up mid-receive
        assertThat(retryable(response(421))).isTrue(); // Misdirected Request: retry on another connection
        assertThat(retryable(response(425))).isTrue(); // Too Early: retry after the handshake
    }

    @Test
    void doesNotRetryDeterministicClientErrors() {
        assertThat(retryable(response(400))).isFalse();
        assertThat(retryable(response(401))).isFalse();
        assertThat(retryable(response(404))).isFalse();
        assertThat(retryable(response(409))).isFalse();
        assertThat(retryable(response(422))).isFalse();
    }

    @Test
    void doesNotRetryFailuresRaisedOnOurOwnSideOfTheExchange() {
        // These are the ones a deny-list lets through. Each is deterministic, so a retry cannot fix it,
        // and re-sending the request turns a local bug into a repeated remote side effect: an NPE in the
        // caller's own .map() after a 200 would re-POST the charge three more times.
        assertThat(retryable(new NullPointerException())).isFalse();
        assertThat(retryable(new IllegalStateException("a 4xx mapped by onStatus"))).isFalse();
        assertThat(retryable(new IllegalArgumentException("rejected idempotency key"))).isFalse();
        assertThat(retryable(new IdempotencyKeyConflictException("order-1"))).isFalse();
        assertThat(retryable(new CancellationException())).isFalse();
    }

    @Test
    void retriesAResponseWhoseTransportDiedBeforeTheBodyArrived() {
        WebClientResponseException truncated = response(200);
        truncated.initCause(new SocketException("Connection reset"));

        // 200 is neither 5xx nor 429, so keying off the status alone refuses to retry the one failure
        // this library exists for: the request was processed and we never learned the outcome.
        assertThat(retryable(truncated)).isTrue();
    }

    @Test
    void doesNotRetryAClientErrorEvenIfItsBodyWasTruncated() {
        WebClientResponseException truncated = response(400);
        truncated.initCause(new SocketException("Connection reset"));

        assertThat(retryable(truncated)).isFalse(); // the status arrived, so the outcome is known
    }

    private static boolean retryable(Throwable error) {
        return IdemClientAutoConfiguration.isRetryable(error);
    }

    private static WebClientResponseException response(int status) {
        return WebClientResponseException.create(status, "test", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Configuration
    static class CustomExecutorConfig {
        @Bean
        IdempotentExecutor customExecutor() {
            return new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(1));
        }
    }

    @Configuration
    static class CustomGeneratorConfig {
        @Bean
        IdempotencyKeyGenerator customGenerator() {
            return () -> "fixed-key";
        }
    }
}
