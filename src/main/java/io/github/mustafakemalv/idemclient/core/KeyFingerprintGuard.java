package io.github.mustafakemalv.idemclient.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Guards against reusing an idempotency key with a DIFFERENT request fingerprint (the classic
 * "same Idempotency-Key, different body" bug that a provider like Stripe rejects with a 400). It
 * remembers, in a bounded in-memory LRU, the fingerprint first seen for each key; a later use of the
 * same key with a different fingerprint fails fast with an {@link IdempotencyKeyConflictException},
 * before the request is sent.
 *
 * <p>In-memory and per-process only: it catches a local caller mistake, not a cross-process conflict.
 */
public final class KeyFingerprintGuard {

    private final Map<String, String> seen;

    public KeyFingerprintGuard(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /**
     * Records the fingerprint for {@code key} on first use; throws if {@code key} was already seen with
     * a different fingerprint.
     */
    public void check(String key, String fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        synchronized (seen) {
            String existing = seen.get(key);
            if (existing == null) {
                seen.put(key, fingerprint);
            } else if (!existing.equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(key);
            }
        }
    }
}
