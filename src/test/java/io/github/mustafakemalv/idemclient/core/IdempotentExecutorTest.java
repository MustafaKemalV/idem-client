package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

class IdempotentExecutorTest {

    private final IdempotentExecutor executor =
            new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(5));

    @Test
    void sameKeyOnEveryRetryAttempt() {
        List<String> seenKeys = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        Mono<String> flaky = Mono.deferContextual(ctx -> {
            IdempotencyContext.keyFrom(ctx).ifPresent(seenKeys::add);
            if (attempts.incrementAndGet() < 3) {
                return Mono.error(new IllegalStateException("transient failure"));
            }
            return Mono.just("ok");
        });

        StepVerifier.create(executor.execute(flaky))
                .expectNext("ok")
                .verifyComplete();

        assertThat(seenKeys).hasSize(3);                 // three attempts each observed a key
        assertThat(new HashSet<>(seenKeys)).hasSize(1);  // and it was the SAME key every time
    }

    @Test
    void distinctOperationsGetDistinctKeys() {
        List<String> keys = new CopyOnWriteArrayList<>();
        Mono<String> capturesKey = Mono.deferContextual(ctx -> {
            IdempotencyContext.keyFrom(ctx).ifPresent(keys::add);
            return Mono.just("ok");
        });

        StepVerifier.create(executor.execute(capturesKey)).expectNext("ok").verifyComplete();
        StepVerifier.create(executor.execute(capturesKey)).expectNext("ok").verifyComplete();

        assertThat(keys).hasSize(2);
        assertThat(new HashSet<>(keys)).hasSize(2);  // two separate operations => two different keys
    }

    @Test
    void freshKeyPerSubscription() {
        List<String> keys = new CopyOnWriteArrayList<>();
        Mono<String> capturesKey = Mono.deferContextual(ctx -> {
            IdempotencyContext.keyFrom(ctx).ifPresent(keys::add);
            return Mono.just("ok");
        });
        Mono<String> executed = executor.execute(capturesKey); // built ONCE

        StepVerifier.create(executed).expectNext("ok").verifyComplete();
        StepVerifier.create(executed).expectNext("ok").verifyComplete();

        assertThat(keys).hasSize(2);
        assertThat(new HashSet<>(keys)).hasSize(2); // two subscriptions => two different keys
    }

    @Test
    void callerSuppliedKeyIsUsed() {
        List<String> keys = new CopyOnWriteArrayList<>();
        Mono<String> capturesKey = Mono.deferContextual(ctx -> {
            IdempotencyContext.keyFrom(ctx).ifPresent(keys::add);
            return Mono.just("ok");
        });

        StepVerifier.create(executor.execute("my-key", capturesKey)).expectNext("ok").verifyComplete();

        assertThat(keys).containsExactly("my-key");
    }

    @Test
    void timesOutSlowAttemptAndRetries() {
        AtomicInteger attempts = new AtomicInteger();
        IdempotentExecutor timeoutExecutor = new IdempotentExecutor(
                new UuidIdempotencyKeyGenerator(), Retry.max(3), Duration.ofMillis(50));
        Mono<String> slowThenFast = Mono.defer(() -> attempts.incrementAndGet() == 1
                ? Mono.delay(Duration.ofMillis(500)).thenReturn("slow") // first attempt times out
                : Mono.just("ok"));

        StepVerifier.create(timeoutExecutor.execute(slowThenFast)).expectNext("ok").verifyComplete();

        assertThat(attempts.get()).isEqualTo(2); // first timed out, second succeeded
    }
}
