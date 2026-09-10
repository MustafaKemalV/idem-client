package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyFingerprintGuardTest {

    private final KeyFingerprintGuard guard = new KeyFingerprintGuard(100);

    @Test
    void allowsTheSameKeyWithTheSameFingerprint() {
        guard.check("client-a", "order-1", "fp-A");

        assertThatCode(() -> guard.check("client-a", "order-1", "fp-A")).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheSameKeyWithADifferentFingerprint() {
        guard.check("client-a", "order-1", "fp-A");

        assertThatThrownBy(() -> guard.check("client-a", "order-1", "fp-B"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void distinctKeysDoNotInterfere() {
        guard.check("client-a", "order-1", "fp-A");

        assertThatCode(() -> guard.check("client-a", "order-2", "fp-B")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAMaxEntriesBelowOne() {
        assertThatThrownBy(() -> new KeyFingerprintGuard(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneKeyUsedAgainstTwoDownstreamsIsNotAConflict() {
        // order-42 charged at a card processor and recorded in a ledger: same internal identifier, two
        // different operations with different bodies. Blocking the second would refuse a legitimate call.
        guard.check("client-a", "order-42", "charge-body");

        assertThatCode(() -> guard.check("client-b", "order-42", "ledger-body")).doesNotThrowAnyException();
    }

    @Test
    void keepsOnlyADigestOfTheFingerprint() {
        // Nothing sensitive should survive in the guard, and every entry should be a fixed size whatever
        // the caller passed in. The digest is deterministic, so the conflict check is unaffected.
        String body = "{\"pan\":\"4111111111111111\",\"amount\":9900}";
        guard.check("client-a", "order-7", body);

        assertThat(IdempotencyKeys.of(body)).hasSize(64);
        assertThatCode(() -> guard.check("client-a", "order-7", body)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.check("client-a", "order-7", body + " "))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void theConflictMessageDoesNotCarryTheWholeKey() {
        // The message ends up in logs and often in an error response; the key may be derived from a
        // business identifier, or be a token the downstream will honour.
        guard.check("client-a", "pan-4111111111111111", "fp-A");

        assertThatThrownBy(() -> guard.check("client-a", "pan-4111111111111111", "fp-B"))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("pan-4111...")
                .hasMessageNotContaining("4111111111111111")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(IdempotencyKeyConflictException.class))
                .extracting(IdempotencyKeyConflictException::idempotencyKey)
                .isEqualTo("pan-4111111111111111"); // still reachable for code that needs it
    }

    @Test
    void forgetsTheLeastRecentlyUsedKeyWhenFull() {
        // Documents the boundary honestly: eviction is protection loss, and the guard says so once in
        // the log rather than pretending it remembers everything.
        KeyFingerprintGuard small = new KeyFingerprintGuard(2);
        small.check("client-a", "k1", "fp-A");
        small.check("client-a", "k2", "fp-B");
        small.check("client-a", "k3", "fp-C"); // evicts k1

        assertThatCode(() -> small.check("client-a", "k1", "totally-different"))
                .doesNotThrowAnyException();
    }
}
