package io.github.mustafakemalv.idemclient.core;

import java.util.Optional;

/**
 * SPI (design preview, v2) for a caller-side idempotency store that lets a repeated logical operation
 * short-circuit instead of re-issuing its side effect. This is NOT wired into the execution path in
 * this release: the interface documents the intended shape so a durable/distributed backend (Redis,
 * JDBC) can be added later without breaking the core API.
 *
 * <p><b>Honest boundary:</b> even a durable store cannot make an operation exactly-once. The window
 * between "the side effect was committed downstream" and "the outcome was persisted here" is not
 * closable on the caller side; a crash inside it still risks a duplicate. A store reduces duplicates
 * for double-submits, queue replays, and restarts; it does not deliver exactly-once.
 *
 * <p>An implementation must be keyed by a STABLE, caller-supplied or DETERMINISTIC key (see
 * {@link IdempotencyKeys}); the default random-UUID generator mints a new key per subscription, so a
 * store keyed by it would never match a prior attempt.
 */
public interface IdempotencyStore {

    /** State of a logical operation under a given key. */
    enum State {
        IN_FLIGHT,
        COMPLETED,
        FAILED,
        UNKNOWN
    }

    /**
     * Atomically marks {@code key} as in-flight if it is currently unknown, returning the prior state.
     * A single-flight implementation uses this so only the first caller proceeds while concurrent
     * callers observe {@link State#IN_FLIGHT}.
     */
    State begin(String key);

    /** Records the serialized outcome for a completed key. */
    void complete(String key, byte[] serializedResponse);

    /** Marks a key failed so it can be retried as a fresh operation. */
    void fail(String key);

    /** Returns the stored outcome for a completed key, if present. */
    Optional<byte[]> find(String key);

    /** A no-op store: it remembers nothing, so every call executes normally. */
    IdempotencyStore NONE = new IdempotencyStore() {
        @Override
        public State begin(String key) {
            return State.UNKNOWN;
        }

        @Override
        public void complete(String key, byte[] serializedResponse) {
        }

        @Override
        public void fail(String key) {
        }

        @Override
        public Optional<byte[]> find(String key) {
            return Optional.empty();
        }
    };
}
