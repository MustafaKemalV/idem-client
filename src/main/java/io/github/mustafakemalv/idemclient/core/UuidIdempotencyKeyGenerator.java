package io.github.mustafakemalv.idemclient.core;

import java.util.UUID;

/**
 * Default {@link IdempotencyKeyGenerator}: a random (version 4) UUID per logical operation.
 *
 * <p>A UUIDv4 carries 122 bits of randomness, so collisions are astronomically unlikely, and it
 * leaks no business data, unlike a sequential counter or a content-derived key.
 */
public final class UuidIdempotencyKeyGenerator implements IdempotencyKeyGenerator {

    @Override
    public String newKey() {
        return UUID.randomUUID().toString();
    }
}
