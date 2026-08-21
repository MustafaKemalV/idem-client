package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class UuidIdempotencyKeyGeneratorTest {

    private final IdempotencyKeyGenerator generator = new UuidIdempotencyKeyGenerator();

    @Test
    void producesAParseableUuid() {
        String key = generator.newKey();
        // fromString does not throw => it is a well-formed UUID
        assertThat(UUID.fromString(key)).hasToString(key);
    }

    @Test
    void producesAFreshKeyEachCall() {
        long distinct = Stream.generate(generator::newKey).limit(1000).distinct().count();
        assertThat(distinct).isEqualTo(1000);
    }
}
