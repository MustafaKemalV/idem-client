# idem-client

[![CI](https://github.com/MustafaKemalV/idem-client/actions/workflows/ci.yml/badge.svg)](https://github.com/MustafaKemalV/idem-client/actions/workflows/ci.yml)

Caller-side idempotency for outbound HTTP in Spring WebFlux. `idem-client` attaches a stable
`Idempotency-Key` to your outgoing requests and keeps the same key across a reactive retry, so an
idempotent downstream (for example a payment provider) does not double-process a request just
because it was retried.

> **Honest scope:** this is not "exactly-once". It gives an at-most-once *effect* by carrying a
> stable idempotency-key across retries. The library does not make the downstream idempotent; it
> assumes the downstream honors the `Idempotency-Key` header. If the downstream ignores the key,
> there is no protection.

## Why caller-side?

Existing Spring idempotency libraries are receiver-side (they deduplicate *inbound* requests).
`idem-client` is the missing caller-side piece: it keeps the key stable on the *outbound* path,
including across a Reactor `retryWhen` retry, where a `ThreadLocal` would be lost on the reactive
thread-hop.

## Features

- **Stable key across retries:** the key lives in the Reactor Context, so every retry attempt of one
  logical operation carries the same `Idempotency-Key`.
- **Per-operation keys:** a fresh key per subscription; a new logical operation gets a new key.
- **Bring your own key or scheme:** pass an explicit key, or plug in your own `IdempotencyKeyGenerator`.
- **Footgun-free wrapper:** `IdempotentWebClient` attaches the filter for you, so a call cannot
  silently go out without the key.
- **Transient-only retries:** retries 5xx/429 and transport errors, not deterministic 4xx.
- **Spring Boot starter:** auto-configured beans, tunable via `idem-client.*` properties.
- **Reactive-first:** built on `WebClient` and Project Reactor.

## Requirements

- Java 25
- Spring Boot 4.1 (Spring WebFlux / `WebClient`)

## Installation

Not yet published to Maven Central. Build and install it into your local Maven repository:

    git clone https://github.com/MustafaKemalV/idem-client.git
    cd idem-client
    mvn install

Then add the dependency:

    <dependency>
        <groupId>io.github.mustafakemalv</groupId>
        <artifactId>idem-client-spring-boot-starter</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>

## Usage

### Recommended: `IdempotentWebClient` (the filter cannot be forgotten)

The starter auto-configures an `IdempotentWebClientFactory`. Hand it your own `WebClient.Builder`
(base URL, auth, codecs, whatever you need); it attaches the idempotency filter and pairs it with the
executor, so every call sends a stable `Idempotency-Key`:

    @Configuration
    class PaymentConfig {
        @Bean
        IdempotentWebClient paymentClient(IdempotentWebClientFactory factory) {
            return factory.create(WebClient.builder().baseUrl("https://payments.example.com"));
        }
    }

    @Service
    class PaymentService {
        private final IdempotentWebClient client;

        PaymentService(IdempotentWebClient client) {
            this.client = client;
        }

        Mono<Receipt> charge(ChargeRequest request) {
            return client.execute(wc -> wc.post().uri("/charge").bodyValue(request)
                    .retrieve().bodyToMono(Receipt.class));
        }
    }

- **Same operation, retried:** every attempt sends the same `Idempotency-Key`.
- **A new `charge(...)` call:** a new key, a distinct operation to the downstream.
- **Your own key:** `client.execute("order-42", wc -> ...)` uses the key you supply.

For multiple downstreams with different header names or retry policies, pass a provider-specific
filter or executor: `factory.create(builder, new IdempotencyKeyExchangeFilter("X-Idem"))`.

### Low-level: `IdempotentExecutor` + filter (wire it yourself)

If you manage the `WebClient` yourself, add the `IdempotencyKeyExchangeFilter` to it and route calls
through the `IdempotentExecutor`:

    WebClient webClient = WebClient.builder()
            .baseUrl("https://payments.example.com")
            .filter(idempotencyFilter) // REQUIRED: without this filter, no key is sent
            .build();

    Mono<Receipt> receipt = idempotentExecutor.execute(
            webClient.post().uri("/charge").bodyValue(request).retrieve().bodyToMono(Receipt.class));

> **Warning:** on the low-level path you MUST add the filter to the WebClient. If you wrap a call in
> `execute(...)` but forget the filter, the call still runs and retries but sends no `Idempotency-Key`
> header, so you get no protection. Prefer `IdempotentWebClient` above, which attaches it for you.

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `idem-client.enabled` | `true` | Turn the auto-configuration on or off. |
| `idem-client.header-name` | `Idempotency-Key` | Header the key is written to. |
| `idem-client.max-attempts` | `3` | Retry attempts, in addition to the initial call. |
| `idem-client.min-backoff` | `100ms` | Minimum exponential backoff between retries. |
| `idem-client.max-backoff` | `2s` | Maximum exponential backoff between retries. |
| `idem-client.per-attempt-timeout` | (unset) | Per-attempt timeout; a timed-out attempt is retried as a transport error. Unset = no timeout. |

## Retry behavior

The auto-configured `IdempotentExecutor` retries transient failures only (HTTP 5xx and 429, and
transport errors such as a connection reset or timeout), up to `max-attempts`, with exponential
backoff and jitter capped at `max-backoff`. Deterministic 4xx errors are not retried. When retries
are exhausted the original error is propagated (not wrapped in a `RetryExhaustedException`). To change
any of this, define your own `IdempotentExecutor` (or `Retry`) bean; every auto-configured bean backs
off when you provide your own.

Set `per-attempt-timeout` (and your WebClient's `responseTimeout`) to bound a slow downstream: a
timed-out attempt is retried safely precisely because the key stays stable.

## Observability

The library requires no observability dependency. Implement `IdempotencyListener` as a bean to feed
any metrics or tracing system; it replaces the no-op default:

    @Bean
    IdempotencyListener idempotencyListener(MeterRegistry registry) {
        return new IdempotencyListener() {
            @Override public void onRetry(long attempt) { registry.counter("idem.retries").increment(); }
            @Override public void onExhausted() { registry.counter("idem.retries.exhausted").increment(); }
        };
    }

The filter also logs at DEBUG when it stamps a key (the key is truncated in the log). For distributed
tracing, the key lives in the Reactor Context: enable Reactor's automatic context propagation
(`Hooks.enableAutomaticContextPropagation()`) and read the key from the Context to add it as a span
tag, rather than from a ThreadLocal-backed MDC, which the reactive thread-hop would lose.

## How it works

See [docs/how-it-works.md](docs/how-it-works.md) for the Reactor Context mechanics and why the key
survives a retry.

## Limitations

- **Not exactly-once.** It is an at-most-once *effect*, only as strong as the downstream's own
  idempotency.
- **The downstream must honor the header.** If it ignores `Idempotency-Key`, there is no protection.
- **Reactive only.** v1 targets `WebClient`; there is no blocking (RestTemplate/Feign) variant.
- **Key scope is one subscription.** Each subscription of a returned `Mono` gets its own key; a retry
  of that subscription keeps the same key. An explicit key must be unique per logical operation.

## Durability boundary

The generated key lives only for the life of one reactive subscription (it is minted in `Mono.defer`
and stored in the subscription-scoped Reactor Context). It does NOT survive a process restart or a new
subscription: if the JVM crashes after a request is dispatched but before the response is processed, and
the operation is later re-driven (a new subscription, or an outbox/queue replay), a brand-new key is
generated and the downstream sees a different logical operation, so it can double-process.

For at-least-once redelivery (crash-safe retries across restarts or pods), do not rely on a generated
key. Derive a stable business key from the operation itself (for example `order-42`, or a hash of the
business identity), persist it alongside the operation, and pass it explicitly:

    idempotency.execute("order-42", wc -> wc.post().uri("/charge")...);

Or derive a stable, non-leaking key (SHA-256) from your business identity with the built-in helper:

    idempotency.execute(IdempotencyKeys.of("charge", orderId), wc -> wc.post().uri("/charge")...);

That way a replay after a crash reuses the same key and the downstream deduplicates it. Genuine
cross-process durability (a persisted key + response store) is intentionally out of scope for this
transport-only library.

## License

MIT. See [LICENSE](LICENSE).
