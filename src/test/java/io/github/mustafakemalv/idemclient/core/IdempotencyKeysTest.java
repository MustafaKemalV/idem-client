package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void sameInputsProduceSameKey() {
        assertThat(IdempotencyKeys.of("charge", "order-42"))
                .isEqualTo(IdempotencyKeys.of("charge", "order-42"));
    }

    @Test
    void differentInputsProduceDifferentKeys() {
        assertThat(IdempotencyKeys.of("charge", "order-42"))
                .isNotEqualTo(IdempotencyKeys.of("charge", "order-43"));
    }

    @Test
    void isLengthPrefixedAgainstCollisions() {
        assertThat(IdempotencyKeys.of("a", "bc")).isNotEqualTo(IdempotencyKeys.of("ab", "c"));
    }

    @Test
    void producesLowercaseHex64() {
        assertThat(IdempotencyKeys.of("x")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(IdempotencyKeys::of).isInstanceOf(IllegalArgumentException.class);
    }
}
