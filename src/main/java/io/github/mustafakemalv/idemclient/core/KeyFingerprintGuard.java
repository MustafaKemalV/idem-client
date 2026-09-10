package io.github.mustafakemalv.idemclient.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Guards against reusing an idempotency key with a DIFFERENT request fingerprint (the classic
 * "same Idempotency-Key, different body" bug that a provider like Stripe rejects with a 400). It
 * remembers, in a bounded in-memory LRU, the fingerprint first seen for each key; a later use of the
 * same key with a different fingerprint fails fast with an {@link IdempotencyKeyConflictException},
 * before the request is sent.
 *
 * <p>In-memory and per-process only: it catches a local caller mistake, not a cross-process conflict.
 *
 * <p>What it stores is a SHA-256 digest of the fingerprint, never the fingerprint itself. The caller
 * decides what a fingerprint is and may reasonably hand over a serialized request body, so keeping it
 * verbatim would hold card numbers and names in the heap, and would make the cap a bound on the number
 * of entries rather than on memory. Digesting fixes both: every entry is 64 characters, and nothing
 * sensitive is retained.
 *
 * @since 0.1.0
 */
public final class KeyFingerprintGuard {

    private static final Log log = LogFactory.getLog(KeyFingerprintGuard.class);

    /** Separator for the composite map key. A scope is always "client-N", so it can never contain this. */
    private static final String SCOPE_SEPARATOR = "::";

    private final AtomicBoolean evictionReported = new AtomicBoolean();
    private final Map<String, String> seen;

    public KeyFingerprintGuard(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() <= maxEntries) {
                    return false;
                }
                reportFirstEviction(maxEntries);
                return true;
            }
        });
    }

    /**
     * Records the fingerprint for {@code key} within {@code scope} on first use; throws if that key was
     * already seen in the same scope with a different fingerprint.
     *
     * @param scope isolates one caller's keys from another's. Two providers keyed by the same internal
     *     identifier (an {@code order-42} sent both to a card processor and to a ledger) are different
     *     operations with different bodies, and without a scope the second, entirely legitimate call
     *     would be refused locally and never sent.
     */
    public void check(String scope, String key, String fingerprint) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        String entry = scope + SCOPE_SEPARATOR + key;
        String digest = IdempotencyKeys.of(fingerprint);
        synchronized (seen) {
            String existing = seen.get(entry);
            if (existing == null) {
                seen.put(entry, digest);
            } else if (!existing.equals(digest)) {
                throw new IdempotencyKeyConflictException(key);
            }
        }
    }

    /**
     * Eviction is silent protection loss: once a key has been forgotten, reusing it with a different
     * request is no longer caught. Reported once, because after the LRU fills every insert evicts and a
     * message per eviction would be noise rather than a signal.
     */
    private void reportFirstEviction(int maxEntries) {
        if (evictionReported.compareAndSet(false, true) && log.isWarnEnabled()) {
            log.warn("fingerprint guard is full at " + maxEntries + " entries and has started forgetting "
                    + "the least recently used keys. A forgotten key reused with a different request is "
                    + "no longer caught locally; raise the size if your operations outlive the window.");
        }
    }
}
