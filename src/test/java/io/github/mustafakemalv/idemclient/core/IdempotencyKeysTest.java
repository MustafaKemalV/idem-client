package io.github.mustafakemalv.idemclient.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void isDeterministic() {
        assertThat(IdempotencyKeys.of("charge", "order-42"))
                .isEqualTo(IdempotencyKeys.of("charge", "order-42"));
    }

    @Test
    void differentPartsGiveDifferentKeys() {
        assertThat(IdempotencyKeys.of("charge", "order-42"))
                .isNotEqualTo(IdempotencyKeys.of("charge", "order-43"));
    }

    @Test
    void isLengthPrefixedAgainstCollisions() {
        assertThat(IdempotencyKeys.of("a", "bc")).isNotEqualTo(IdempotencyKeys.of("ab", "c"));
    }

    @Test
    void rejectsNoParts() {
        assertThatThrownBy(IdempotencyKeys::of).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullPart() {
        assertThatThrownBy(() -> IdempotencyKeys.of("charge", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theEncodingIsPinned() {
        // The digest of "6:charge8:order-42". A caller persists these keys and replays them after a
        // crash, so changing the encoding would silently change every key already written down. If this
        // test goes red, that is not a test to update, it is a breaking change to think about.
        assertThat(IdempotencyKeys.of("charge", "order-42"))
                .isEqualTo("84ee576a40f4e12d5b57f5cb083f0a4b5a9fa085e320dda7bac4bfee7fbec08e");
    }

    @Test
    void theLengthPrefixCountsBytesNotCharacters() {
        // "turkiye" with a u-umlaut is 7 characters but 8 UTF-8 bytes. The prefix has to describe what
        // actually follows it, or the framing that the collision argument rests on is not framing.
        assertThat(IdempotencyKeys.of("türkiye"))
                .isEqualTo("1d213ca9bcd7b431a92916cdf2a735e5f87f3c312e9dd9948e9ade9ccc41f0ce");
    }

    @Test
    void hmacIsDeterministicAndSecretDependent() {
        byte[] secret = "s3cr3t".getBytes(StandardCharsets.UTF_8);

        assertThat(IdempotencyKeys.hmac(secret, "charge", "order-42"))
                .isEqualTo(IdempotencyKeys.hmac(secret, "charge", "order-42"));
        assertThat(IdempotencyKeys.hmac(secret, "charge", "order-42"))
                .isNotEqualTo(IdempotencyKeys.hmac("other".getBytes(StandardCharsets.UTF_8), "charge", "order-42"));
        // and it is not the unkeyed digest, which anyone can recompute
        assertThat(IdempotencyKeys.hmac(secret, "charge", "order-42"))
                .isNotEqualTo(IdempotencyKeys.of("charge", "order-42"));
    }

    @Test
    void theHmacEncodingIsPinnedToo() {
        // HMAC-SHA256 of "6:charge8:order-42" under the key "s3cr3t", computed with openssl. Same
        // framing as of(...), so the two helpers cannot drift apart.
        assertThat(IdempotencyKeys.hmac("s3cr3t".getBytes(StandardCharsets.UTF_8), "charge", "order-42"))
                .isEqualTo("6ab9ecf4e16c91cd529213d7a8e75c082ecb550b91cc8bdd1d01a7b189230816");
    }

    @Test
    void hmacRejectsAnEmptySecret() {
        assertThatThrownBy(() -> IdempotencyKeys.hmac(new byte[0], "charge"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.hmac(null, "charge"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hmacRejectsNoParts() {
        assertThatThrownBy(() -> IdempotencyKeys.hmac("s3cr3t".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alwaysProducesAKeyTheLibraryWillAccept() {
        assertThat(IdempotencyKeys.of("charge", "order-42")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdempotencyKeys.hmac("s3cr3t".getBytes(StandardCharsets.UTF_8), "charge", "order-42"))
                .hasSize(64).matches("[0-9a-f]{64}");
    }
}
