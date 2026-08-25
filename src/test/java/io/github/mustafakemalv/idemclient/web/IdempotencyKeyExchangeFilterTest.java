package io.github.mustafakemalv.idemclient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mustafakemalv.idemclient.core.IdempotencyContext;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdempotencyKeyExchangeFilterTest {

    private final IdempotencyKeyExchangeFilter filter = new IdempotencyKeyExchangeFilter();

    private ClientRequest newRequest() {
        return ClientRequest.create(HttpMethod.POST, URI.create("http://downstream/charge")).build();
    }

    private ExchangeFunction capturingInto(AtomicReference<ClientRequest> sink) {
        return req -> {
            sink.set(req);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
    }

    @Test
    void addsHeaderFromContextKey() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        Mono<ClientResponse> result = filter.filter(newRequest(), capturingInto(captured))
                .contextWrite(ctx -> IdempotencyContext.withKey(ctx, "key-abc"));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertThat(captured.get().headers().getFirst("Idempotency-Key")).isEqualTo("key-abc");
    }

    @Test
    void noHeaderWhenContextHasNoKey() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        Mono<ClientResponse> result = filter.filter(newRequest(), capturingInto(captured));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertThat(captured.get().headers().containsHeader("Idempotency-Key")).isFalse();
    }

    @Test
    void doesNotOverrideExplicitHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ClientRequest explicit = ClientRequest.from(newRequest())
                .header("Idempotency-Key", "caller-set")
                .build();

        Mono<ClientResponse> result = filter.filter(explicit, capturingInto(captured))
                .contextWrite(ctx -> IdempotencyContext.withKey(ctx, "key-abc"));

        StepVerifier.create(result).expectNextCount(1).verifyComplete();
        assertThat(captured.get().headers().getFirst("Idempotency-Key")).isEqualTo("caller-set");
    }

    @Test
    void rejectsKeyWithControlCharacters() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        Mono<ClientResponse> result = filter.filter(newRequest(), capturingInto(captured))
                .contextWrite(ctx -> IdempotencyContext.withKey(ctx, "bad\r\nX-Evil: 1"));

        StepVerifier.create(result).expectError(IllegalArgumentException.class).verify();
        assertThat(captured.get()).isNull(); // request never dispatched
    }

    @Test
    void rejectsBlankHeaderName() {
        assertThatThrownBy(() -> new IdempotencyKeyExchangeFilter("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullHeaderName() {
        assertThatThrownBy(() -> new IdempotencyKeyExchangeFilter(null))
                .isInstanceOf(NullPointerException.class);
    }
}
