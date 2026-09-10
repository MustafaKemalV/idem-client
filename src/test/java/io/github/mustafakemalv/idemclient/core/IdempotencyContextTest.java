package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class IdempotencyContextTest {

    @Test
    void keyWrittenBelowIsReadableAbove() {
        // deferContextual is UPSTREAM (source); contextWrite is DOWNSTREAM (below it).
        // Data flows down, but Context flows UP, so the upstream read sees the downstream write.
        Mono<Optional<String>> pipeline =
                Mono.deferContextual(ctx -> Mono.just(IdempotencyContext.keyFrom(ctx)))
                        .contextWrite(ctx -> IdempotencyContext.withKey(ctx, "key-123"));

        StepVerifier.create(pipeline)
                .assertNext(found -> assertThat(found).contains("key-123"))
                .verifyComplete();
    }

    @Test
    void absentKeyReadsAsEmpty() {
        Mono<Optional<String>> pipeline =
                Mono.deferContextual(ctx -> Mono.just(IdempotencyContext.keyFrom(ctx)));

        StepVerifier.create(pipeline)
                .assertNext(found -> assertThat(found).isEmpty())
                .verifyComplete();
    }

    @Test
    void aRetryOfTheSameRequestIsNotReportedAsKeyReuse() {
        Context context = IdempotencyContext.withKey(Context.empty(), "key-123");

        // Every attempt of one operation stamps the same request under the same Context.
        assertThat(IdempotencyContext.recordStampedRequest(context, "POST /charge")).isEmpty();
        assertThat(IdempotencyContext.recordStampedRequest(context, "POST /charge")).isEmpty();
        assertThat(IdempotencyContext.recordStampedRequest(context, "POST /charge")).isEmpty();
    }

    @Test
    void aSecondDifferentRequestUnderOneKeyIsReported() {
        Context context = IdempotencyContext.withKey(Context.empty(), "key-123");

        assertThat(IdempotencyContext.recordStampedRequest(context, "POST /charge")).isEmpty();
        assertThat(IdempotencyContext.recordStampedRequest(context, "POST /confirm"))
                .contains("POST /charge"); // reports the operation the key really belongs to
    }

    @Test
    void anUnwrittenContextReportsNothing() {
        assertThat(IdempotencyContext.recordStampedRequest(Context.empty(), "POST /charge")).isEmpty();
    }

    @Test
    void eachSubscriptionGetsItsOwnScopeMarker() {
        // withKey runs per subscription, so two operations never share the marker.
        Context first = IdempotencyContext.withKey(Context.empty(), "key-1");
        Context second = IdempotencyContext.withKey(Context.empty(), "key-2");

        assertThat(IdempotencyContext.recordStampedRequest(first, "POST /charge")).isEmpty();
        assertThat(IdempotencyContext.recordStampedRequest(second, "POST /confirm")).isEmpty();
    }

    @Test
    void keySurvivesThreadHopWhereThreadLocalWouldNot() {
        ThreadLocal<String> threadLocal = new ThreadLocal<>();
        AtomicReference<String> subscribeThread = new AtomicReference<>();
        AtomicReference<String> readThread = new AtomicReference<>();
        AtomicReference<String> keyOnOtherThread = new AtomicReference<>();
        AtomicReference<String> threadLocalOnOtherThread = new AtomicReference<>();

        Mono<String> pipeline = Mono.fromRunnable(() -> {
                    subscribeThread.set(Thread.currentThread().getName());
                    threadLocal.set("tl-value"); // set on the subscribing thread
                })
                .then(Mono.just("x"))
                .publishOn(Schedulers.boundedElastic()) // hop to another thread
                .flatMap(x -> Mono.deferContextual(ctx -> {
                    readThread.set(Thread.currentThread().getName());
                    keyOnOtherThread.set(IdempotencyContext.keyFrom(ctx).orElse(null));
                    threadLocalOnOtherThread.set(threadLocal.get());
                    return Mono.just("done");
                }))
                .contextWrite(ctx -> IdempotencyContext.withKey(ctx, "key-xyz"));

        StepVerifier.create(pipeline).expectNext("done").verifyComplete();

        assertThat(readThread.get()).isNotEqualTo(subscribeThread.get()); // actually hopped threads
        assertThat(keyOnOtherThread.get()).isEqualTo("key-xyz");          // Context SURVIVED the hop
        assertThat(threadLocalOnOtherThread.get()).isNull();              // ThreadLocal did NOT
    }
}
