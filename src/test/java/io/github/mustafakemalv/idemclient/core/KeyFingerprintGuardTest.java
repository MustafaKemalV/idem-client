package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyFingerprintGuardTest {

    private final KeyFingerprintGuard guard = new KeyFingerprintGuard(100);

    @Test
    void allowsTheSameKeyWithTheSameFingerprint() {
        guard.check("order-1", "fp-A");

        assertThatCode(() -> guard.check("order-1", "fp-A")).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheSameKeyWithADifferentFingerprint() {
        guard.check("order-1", "fp-A");

        assertThatThrownBy(() -> guard.check("order-1", "fp-B"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void distinctKeysDoNotInterfere() {
        guard.check("order-1", "fp-A");

        assertThatCode(() -> guard.check("order-2", "fp-B")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAMaxEntriesBelowOne() {
        assertThatThrownBy(() -> new KeyFingerprintGuard(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneKeyUsedForTwoDifferentRequestsIsAConflictWhereverTheySecondWasGoing() {
        // order-42 charged at a card processor and then recorded in a ledger is ONE business event but
        // TWO logical operations, and the library's rule is one key per logical operation. Scoping the
        // guard per client would let this through, which is the mistake the guard exists to catch.
        guard.check("order-42", "charge-body");

        assertThatThrownBy(() -> guard.check("order-42", "ledger-body"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void keepsOnlyADigestOfTheFingerprint() {
        // Nothing sensitive should survive in the guard, and every entry should be a fixed size whatever
        // the caller passed in. The digest is deterministic, so the conflict check is unaffected.
        String body = "{\"pan\":\"4111111111111111\",\"amount\":9900}";
        guard.check("order-7", body);

        assertThat(IdempotencyKeys.of(body)).hasSize(64);
        assertThatCode(() -> guard.check("order-7", body)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.check("order-7", body + " "))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void theConflictMessageDoesNotCarryTheWholeKey() {
        // The message ends up in logs and often in an error response; the key may be derived from a
        // business identifier, or be a token the downstream will honour.
        guard.check("pan-4111111111111111", "fp-A");

        assertThatThrownBy(() -> guard.check("pan-4111111111111111", "fp-B"))
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
        small.check("k1", "fp-A");
        small.check("k2", "fp-B");
        small.check("k3", "fp-C"); // evicts k1

        assertThatCode(() -> small.check("k1", "totally-different"))
                .doesNotThrowAnyException();
    }
}
