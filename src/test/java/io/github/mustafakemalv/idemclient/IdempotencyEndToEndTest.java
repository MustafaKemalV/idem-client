package io.github.mustafakemalv.idemclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.mustafakemalv.idemclient.autoconfigure.IdemClientAutoConfiguration;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClient;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

@WireMockTest
class IdempotencyEndToEndTest {

    private final IdempotentExecutor executor =
            new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(3));

    private WebClient clientFor(WireMockRuntimeInfo wm) {
        return WebClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .filter(new IdempotencyKeyExchangeFilter())
                .build();
    }

    private Mono<String> charge(WebClient client) {
        return client.post().uri("/charge").retrieve().bodyToMono(String.class);
    }

    private List<String> sentKeys() {
        return findAll(postRequestedFor(urlEqualTo("/charge"))).stream()
                .map(req -> req.getHeader("Idempotency-Key"))
                .collect(Collectors.toList());
    }

    @Test
    void retryReusesTheSameKeyOnEveryAttempt(WireMockRuntimeInfo wm) {
        // Downstream fails twice (503), succeeds on the third attempt.
        stubFor(post(urlEqualTo("/charge")).inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        stubFor(post(urlEqualTo("/charge")).inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        stubFor(post(urlEqualTo("/charge")).inScenario("retry")
                .whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        StepVerifier.create(executor.execute(charge(clientFor(wm))))
                .expectNext("ok")
                .verifyComplete();

        verify(3, postRequestedFor(urlEqualTo("/charge")));
        assertThat(sentKeys()).hasSize(3);
        assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L); // SAME key all 3 attempts
    }

    @Test
    void distinctOperationsSendDistinctKeys(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));
        WebClient client = clientFor(wm);

        StepVerifier.create(executor.execute(charge(client))).expectNext("ok").verifyComplete();
        StepVerifier.create(executor.execute(charge(client))).expectNext("ok").verifyComplete();

        verify(2, postRequestedFor(urlEqualTo("/charge")));
        assertThat(sentKeys()).hasSize(2);
        assertThat(sentKeys().stream().distinct().count()).isEqualTo(2L); // two operations => two keys
    }

    @Test
    void keyIsGeneratedWhenNoneSupplied(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));

        StepVerifier.create(executor.execute(charge(clientFor(wm)))).expectNext("ok").verifyComplete();

        String key = sentKeys().get(0);
        assertThat(key).isNotNull();
        assertThat(UUID.fromString(key)).hasToString(key); // a well-formed UUID was generated
    }

    @Test
    void doesNotRetryClientErrors(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(400)));
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(),
                Retry.backoff(3, Duration.ofMillis(1)).filter(IdemClientAutoConfiguration::isRetryable));

        StepVerifier.create(exec.execute(charge(clientFor(wm)))).expectError().verify();

        verify(1, postRequestedFor(urlEqualTo("/charge"))); // 4xx: no retry
    }

    @Test
    void retriesServerErrors(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(503)));
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(),
                Retry.backoff(3, Duration.ofMillis(1)).filter(IdemClientAutoConfiguration::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));

        StepVerifier.create(exec.execute(charge(clientFor(wm))))
                .expectError(WebClientResponseException.class).verify(); // original error, not RetryExhausted

        verify(4, postRequestedFor(urlEqualTo("/charge"))); // 5xx: 1 + 3 retries
    }

    @Test
    void factoryWrappedClientSendsKeyWithoutManualFilter(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));
        IdempotentWebClientFactory factory = new IdempotentWebClientFactory(
                new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(1)),
                new IdempotencyKeyExchangeFilter());
        // the builder has NO .filter(...) call; the factory attaches it for us
        IdempotentWebClient client = factory.create(WebClient.builder().baseUrl(wm.getHttpBaseUrl()));

        StepVerifier.create(client.execute(wc ->
                        wc.post().uri("/charge").retrieve().bodyToMono(String.class)))
                .expectNext("ok").verifyComplete();

        String key = sentKeys().get(0);
        assertThat(UUID.fromString(key)).hasToString(key); // key sent even without a manual filter
    }

    @Test
    void retryAfterConnectionResetReusesSameKey(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).inScenario("reset")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("second"));
        stubFor(post(urlEqualTo("/charge")).inScenario("reset")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(),
                Retry.backoff(3, Duration.ofMillis(1)).filter(IdemClientAutoConfiguration::isRetryable));

        StepVerifier.create(exec.execute(charge(clientFor(wm)))).expectNext("ok").verifyComplete();

        verify(2, postRequestedFor(urlEqualTo("/charge")));
        assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L); // same key across a REAL socket reset
    }
}
