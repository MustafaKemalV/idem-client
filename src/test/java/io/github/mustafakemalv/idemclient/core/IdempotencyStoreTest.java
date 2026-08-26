package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

    @Test
    void noneStoreRemembersNothing() {
        IdempotencyStore store = IdempotencyStore.NONE;

        assertThat(store.begin("k")).isEqualTo(IdempotencyStore.State.UNKNOWN);
        store.complete("k", new byte[] {1, 2, 3});
        store.fail("k");
        assertThat(store.find("k")).isEmpty();
    }
}
