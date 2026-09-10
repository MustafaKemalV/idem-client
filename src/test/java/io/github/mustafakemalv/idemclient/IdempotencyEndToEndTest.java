package io.github.mustafakemalv.idemclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
import io.github.mustafakemalv.idemclient.core.IdempotencyKeyConflictException;
import io.github.mustafakemalv.idemclient.core.IdempotencyListener;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClient;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void aConnectionDyingMidResponseIsReportedWithTheResponseStatusAndAnIoCause(WireMockRuntimeInfo wm) {
        // Documents the Spring behaviour the retry predicate depends on: once the status line has been
        // read, a transport failure while reading the BODY is reported as a WebClientResponseException
        // carrying that status, with the real IOException as its cause.
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)));
        AtomicReference<Throwable> captured = new AtomicReference<>();

        StepVerifier.create(charge(clientFor(wm)).doOnError(captured::set)).expectError().verify();

        assertThat(captured.get()).isInstanceOf(WebClientResponseException.class);
        assertThat(((WebClientResponseException) captured.get()).getStatusCode().value()).isEqualTo(200);
        assertThat(captured.get().getCause()).isInstanceOf(IOException.class);
    }

    @Test
    void retriesAConnectionThatDiesMidResponseAndReusesTheSameKey(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).inScenario("mid-body")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK))
                .willSetStateTo("second"));
        stubFor(post(urlEqualTo("/charge")).inScenario("mid-body")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(),
                Retry.backoff(3, Duration.ofMillis(1)).filter(IdemClientAutoConfiguration::isRetryable));

        StepVerifier.create(exec.execute(charge(clientFor(wm)))).expectNext("ok").verifyComplete();

        verify(2, postRequestedFor(urlEqualTo("/charge")));
        assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L); // same key, so it is safe
    }

    @Test
    void everyListenerEventCarriesTheKeyThatWentOnTheWire(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(503)));
        List<String> minted = new CopyOnWriteArrayList<>();
        List<String> retried = new CopyOnWriteArrayList<>();
        AtomicReference<String> failedKey = new AtomicReference<>();
        AtomicLong failedAttempts = new AtomicLong();
        IdempotencyListener listener = new IdempotencyListener() {
            @Override
            public void onKeyMinted(String idempotencyKey) {
                minted.add(idempotencyKey);
            }

            @Override
            public void onRetry(String idempotencyKey, long attempt) {
                retried.add(idempotencyKey);
            }

            @Override
            public void onFailed(String idempotencyKey, long attempts, Throwable error) {
                failedKey.set(idempotencyKey);
                failedAttempts.set(attempts);
            }
        };
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(),
                Retry.backoff(2, Duration.ofMillis(1)).filter(IdemClientAutoConfiguration::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()),
                null, listener);

        StepVerifier.create(exec.execute(charge(clientFor(wm)))).expectError().verify();

        String key = minted.get(0);
        assertThat(minted).hasSize(1);                    // one logical operation, one minted key
        assertThat(retried).containsExactly(key, key);    // two retries, both under that key
        assertThat(failedKey.get()).isEqualTo(key);
        assertThat(failedAttempts.get()).isEqualTo(3);    // 1 + 2: the request reached the wire 3 times
        // The reconciliation guarantee: the key handed to the listener is the key the server saw, so a
        // caller who logs onFailed can go and ask the downstream what happened to it.
        assertThat(sentKeys()).containsOnly(key);
    }

    @Test
    void perProviderCustomHeaderName(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));
        IdempotentWebClientFactory factory = new IdempotentWebClientFactory(
                new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(1)),
                new IdempotencyKeyExchangeFilter());
        IdempotentWebClient client = factory.create(
                WebClient.builder().baseUrl(wm.getHttpBaseUrl()),
                new IdempotencyKeyExchangeFilter("X-Custom-Idem")); // provider-specific header

        StepVerifier.create(client.execute(wc ->
                        wc.post().uri("/charge").retrieve().bodyToMono(String.class)))
                .expectNext("ok").verifyComplete();

        verify(postRequestedFor(urlEqualTo("/charge")).withHeader("X-Custom-Idem", matching(".+")));
    }

    @Test
    void rejectsSameKeyWithDifferentFingerprint(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));
        IdempotentWebClientFactory factory = new IdempotentWebClientFactory(
                new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(1)),
                new IdempotencyKeyExchangeFilter(),
                new KeyFingerprintGuard(100));
        IdempotentWebClient client = factory.create(WebClient.builder().baseUrl(wm.getHttpBaseUrl()));

        // same key + same fingerprint: allowed, sent twice
        StepVerifier.create(client.execute("order-1", "fp-A",
                        wc -> wc.post().uri("/charge").retrieve().bodyToMono(String.class)))
                .expectNext("ok").verifyComplete();
        StepVerifier.create(client.execute("order-1", "fp-A",
                        wc -> wc.post().uri("/charge").retrieve().bodyToMono(String.class)))
                .expectNext("ok").verifyComplete();

        // same key + different fingerprint: rejected before sending
        StepVerifier.create(client.execute("order-1", "fp-B",
                        wc -> wc.post().uri("/charge").retrieve().bodyToMono(String.class)))
                .expectError(IdempotencyKeyConflictException.class).verify();
    }
}
