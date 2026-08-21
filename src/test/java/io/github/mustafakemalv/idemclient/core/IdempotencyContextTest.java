package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
}
