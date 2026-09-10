package io.github.mustafakemalv.idemclient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.mustafakemalv.idemclient.core.IdempotencyKeyConflictException;
import io.github.mustafakemalv.idemclient.core.IdempotentExecutor;
import io.github.mustafakemalv.idemclient.core.KeyFingerprintGuard;
import io.github.mustafakemalv.idemclient.core.UuidIdempotencyKeyGenerator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

/**
 * The three {@code execute} overloads must behave as one method: the call function runs per
 * subscription, and a bad argument arrives as an onError signal rather than as a thrown exception.
 * Nothing here reaches the network; every case fails, or completes, before a request is dispatched.
 */
class IdempotentWebClientTest {

    private final IdempotentWebClientFactory factory = new IdempotentWebClientFactory(
            new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(3)),
            new IdempotencyKeyExchangeFilter(),
            new KeyFingerprintGuard(10));

    private final IdempotentWebClient client =
            factory.create(WebClient.builder().baseUrl("http://localhost:1"));

    @Test
    void badKeyIsAnErrorSignalNotAThrownException() {
        assertThatCode(() -> client.execute("  ", wc -> Mono.just("never")))
                .doesNotThrowAnyException(); // assembly must stay quiet

        StepVerifier.create(client.execute("  ", wc -> Mono.just("never")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void badKeyIsAnErrorSignalOnTheFingerprintOverloadToo() {
        assertThatCode(() -> client.execute("  ", "fp", wc -> Mono.just("never")))
                .doesNotThrowAnyException();

        StepVerifier.create(client.execute("  ", "fp", wc -> Mono.just("never")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void fingerprintConflictIsAnErrorSignalNotAThrownException() {
        StepVerifier.create(client.execute("order-9", "fp-A", wc -> Mono.just("ok")))
                .expectNext("ok").verifyComplete();

        assertThatCode(() -> client.execute("order-9", "fp-B", wc -> Mono.just("ok")))
                .doesNotThrowAnyException();

        StepVerifier.create(client.execute("order-9", "fp-B", wc -> Mono.just("ok")))
                .expectError(IdempotencyKeyConflictException.class)
                .verify();
    }

    @Test
    void everyOverloadAppliesTheCallOncePerSubscription() {
        AtomicInteger generatedKeyPath = new AtomicInteger();
        AtomicInteger suppliedKeyPath = new AtomicInteger();
        AtomicInteger fingerprintPath = new AtomicInteger();

        List<Mono<String>> built = List.of(
                client.execute(wc -> {
                    generatedKeyPath.incrementAndGet();
                    return Mono.just("ok");
                }),
                client.execute("key-1", wc -> {
                    suppliedKeyPath.incrementAndGet();
                    return Mono.just("ok");
                }),
                client.execute("key-2", "fp", wc -> {
                    fingerprintPath.incrementAndGet();
                    return Mono.just("ok");
                }));

        assertThat(generatedKeyPath).hasValue(0); // assembling builds no request on any path
        assertThat(suppliedKeyPath).hasValue(0);
        assertThat(fingerprintPath).hasValue(0);

        for (Mono<String> operation : built) {
            StepVerifier.create(operation).expectNext("ok").verifyComplete();
            StepVerifier.create(operation).expectNext("ok").verifyComplete();
        }

        assertThat(generatedKeyPath).hasValue(2); // one application per subscription, on all three
        assertThat(suppliedKeyPath).hasValue(2);
        assertThat(fingerprintPath).hasValue(2);
    }
}
