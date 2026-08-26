package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyFingerprintGuardTest {

    private final KeyFingerprintGuard guard = new KeyFingerprintGuard(100);

    @Test
    void sameKeySameFingerprintIsAllowed() {
        guard.check("k1", "fp1");
        assertThatCode(() -> guard.check("k1", "fp1")).doesNotThrowAnyException();
    }

    @Test
    void sameKeyDifferentFingerprintIsRejected() {
        guard.check("k1", "fp1");
        assertThatThrownBy(() -> guard.check("k1", "fp2"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void distinctKeysAreIndependent() {
        guard.check("k1", "fp1");
        assertThatCode(() -> guard.check("k2", "fp2")).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidMaxEntries() {
        assertThatThrownBy(() -> new KeyFingerprintGuard(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
