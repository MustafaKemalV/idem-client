package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
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

    @Test
    void aCancelledOperationIsReportedAsAnUnknownOutcome() {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(0),
                null, new IdempotencyListener() {
                    @Override
                    public void onFailed(String idempotencyKey, long attempts, Throwable error) {
                        failures.add(error);
                    }
                });

        StepVerifier.create(exec.execute(Mono.never())).thenCancel().verify();

        // A cancel leaves a request possibly in flight with nothing to report on it later. Telling the
        // caller only about errors would silently lose exactly those operations.
        assertThat(failures).singleElement().isInstanceOf(CancellationException.class);
    }

    @Test
    void aSuccessfulOperationReportsNoFailure() {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        IdempotentExecutor exec = new IdempotentExecutor(new UuidIdempotencyKeyGenerator(), Retry.max(0),
                null, new IdempotencyListener() {
                    @Override
                    public void onFailed(String idempotencyKey, long attempts, Throwable error) {
                        failures.add(error);
                    }
                });

        StepVerifier.create(exec.execute(Mono.just("ok"))).expectNext("ok").verifyComplete();

        assertThat(failures).isEmpty(); // a completed Mono must not look like a cancelled one
    }

    @Test
    void rejectsBlankKey() {
        // The dangerous case: a blank key is a legal header value, so without this check it would be
        // sent as an empty Idempotency-Key and read downstream as no key at all.
        assertThatThrownBy(() -> executor.execute("", Mono.just("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> executor.execute("   ", Mono.just("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void acceptsKeyAtTheLimitAndRejectsOneCharacterMore() {
        String atLimit = "k".repeat(IdempotentExecutor.MAX_KEY_LENGTH);
        String overLimit = "k".repeat(IdempotentExecutor.MAX_KEY_LENGTH + 1);

        StepVerifier.create(executor.execute(atLimit, Mono.just("ok"))).expectNext("ok").verifyComplete();

        assertThatThrownBy(() -> executor.execute(overLimit, Mono.just("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 255");
    }

    @Test
    void rejectsAnythingOutsidePrintableAscii() {
        List<String> bad = List.of(
                "bad\r\nX-Evil: 1",  // header injection attempt
                "with space",        // 0x20, silently trimmed by HTTP parsers
                "t\tab",             // control character
                "unicode-é",    // non-ASCII, mangled by the header encoder
                "nbsp- ");      // obs-text, rejected by strict proxies

        for (String key : bad) {
            assertThatThrownBy(() -> executor.execute(key, Mono.just("x")))
                    .as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsTheKeysThisLibraryItselfProduces() {
        StepVerifier.create(executor.execute(UUID.randomUUID().toString(), Mono.just("ok")))
                .expectNext("ok").verifyComplete();
        StepVerifier.create(executor.execute(IdempotencyKeys.of("charge", "order-42"), Mono.just("ok")))
                .expectNext("ok").verifyComplete();
    }

    @Test
    void invalidGeneratedKeyFailsOnceAndIsNeverRetried() {
        AtomicInteger generated = new AtomicInteger();
        IdempotentExecutor blankKeyExecutor = new IdempotentExecutor(() -> {
            generated.incrementAndGet();
            return "  ";
        }, Retry.max(3));

        StepVerifier.create(blankKeyExecutor.execute(Mono.just("ok")))
                .expectError(IllegalArgumentException.class)
                .verify();

        // A key that cannot be sent is a caller mistake, not a transient failure: validating above the
        // retry means it costs exactly one attempt, not the whole budget.
        assertThat(generated.get()).isEqualTo(1);
    }
}
