package io.github.mustafakemalv.idemclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
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
import io.github.mustafakemalv.idemclient.web.IdempotentWebClient;
import io.github.mustafakemalv.idemclient.web.IdempotentWebClientFactory;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Everything else builds its own {@code Retry} to test a policy in isolation, which means the
 * behaviour an application actually gets, the auto-configured defaults wired together, was never
 * exercised end to end. These tests use the beans the starter really produces.
 */
@WireMockTest
class AutoConfiguredEndToEndTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdemClientAutoConfiguration.class))
            .withPropertyValues("idem-client.min-backoff=1ms", "idem-client.max-backoff=5ms");

    private static List<String> sentKeys() {
        return findAll(postRequestedFor(urlEqualTo("/charge"))).stream()
                .map(request -> request.getHeader("Idempotency-Key"))
                .collect(Collectors.toList());
    }

    private static Mono<String> charge(IdempotentWebClient client) {
        return client.execute(wc -> wc.post().uri("/charge").retrieve().bodyToMono(String.class));
    }

    private static IdempotentWebClient clientFrom(AssertableApplicationContext context, WireMockRuntimeInfo wm) {
        return context.getBean(IdempotentWebClientFactory.class)
                .create(WebClient.builder().baseUrl(wm.getHttpBaseUrl()));
    }

    @Test
    void defaultsRetryAServerErrorUnderOneStableKey(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).inScenario("s").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        stubFor(post(urlEqualTo("/charge")).inScenario("s").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("third"));
        stubFor(post(urlEqualTo("/charge")).inScenario("s").whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        runner.run(context -> {
            StepVerifier.create(charge(clientFrom(context, wm))).expectNext("ok").verifyComplete();

            verify(3, postRequestedFor(urlEqualTo("/charge")));
            assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L);
        });
    }

    @Test
    void defaultsDoNotRetryAClientError(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(400)));

        runner.run(context -> {
            StepVerifier.create(charge(clientFrom(context, wm))).expectError().verify();

            verify(1, postRequestedFor(urlEqualTo("/charge")));
        });
    }

    @Test
    void defaultsRetryAConnectionThatDiesMidResponse(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).inScenario("s").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK)).willSetStateTo("second"));
        stubFor(post(urlEqualTo("/charge")).inScenario("s").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        runner.run(context -> {
            StepVerifier.create(charge(clientFrom(context, wm))).expectNext("ok").verifyComplete();

            verify(2, postRequestedFor(urlEqualTo("/charge")));
            assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L);
        });
    }

    @Test
    void defaultsExhaustAndPropagateTheOriginalError(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(503)));

        runner.run(context -> {
            StepVerifier.create(charge(clientFrom(context, wm)))
                    .expectError(WebClientResponseException.class) // original error, not RetryExhausted
                    .verify();

            // max-attempts defaults to 3 retries in addition to the initial call
            verify(4, postRequestedFor(urlEqualTo("/charge")));
            assertThat(sentKeys().stream().distinct().count()).isEqualTo(1L);
        });
    }

    @Test
    void aCustomHeaderNameFromPropertiesReachesTheWire(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withStatus(200).withBody("ok")));

        runner.withPropertyValues("idem-client.header-name=X-Idem").run(context -> {
            StepVerifier.create(charge(clientFrom(context, wm))).expectNext("ok").verifyComplete();

            verify(postRequestedFor(urlEqualTo("/charge"))
                    .withHeader("X-Idem", matching(".+")));
        });
    }
}
