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
- **Per-operation keys:** a fresh key per `execute(...)` call; a new logical operation gets a new key.
- **Bring your own key or scheme:** pass an explicit key, or plug in your own `IdempotencyKeyGenerator`.
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

The starter auto-configures an `IdempotentExecutor` and an `IdempotencyKeyExchangeFilter`. Add the
filter to the `WebClient` you use for the idempotent downstream, then run each call through the
executor:

    @Service
    class PaymentClient {

        private final WebClient webClient;
        private final IdempotentExecutor idempotency;

        PaymentClient(IdempotencyKeyExchangeFilter idempotencyFilter, IdempotentExecutor idempotency) {
            this.webClient = WebClient.builder()
                    .baseUrl("https://payments.example.com")
                    .filter(idempotencyFilter)
                    .build();
            this.idempotency = idempotency;
        }

        Mono<Receipt> charge(ChargeRequest request) {
            return idempotency.execute(
                    webClient.post().uri("/charge").bodyValue(request)
                            .retrieve().bodyToMono(Receipt.class));
        }
    }

- **Same operation, retried:** every attempt sends the same `Idempotency-Key`.
- **A new `charge(...)` call:** a new key, a distinct operation to the downstream.
- **Your own key:** `idempotency.execute("order-42", ...)` uses the key you supply.

## Configuration

| Property | Default | Description |
| --- | --- | --- |
| `idem-client.enabled` | `true` | Turn the auto-configuration on or off. |
| `idem-client.header-name` | `Idempotency-Key` | Header the key is written to. |
| `idem-client.max-attempts` | `3` | Retry attempts, in addition to the initial call. |
| `idem-client.min-backoff` | `100ms` | Minimum exponential backoff between retries. |

## How it works

See [docs/how-it-works.md](docs/how-it-works.md) for the Reactor Context mechanics and why the key
survives a retry.

## Limitations

- **Not exactly-once.** It is an at-most-once *effect*, only as strong as the downstream's own
  idempotency.
- **The downstream must honor the header.** If it ignores `Idempotency-Key`, there is no protection.
- **Reactive only.** v1 targets `WebClient`; there is no blocking (RestTemplate/Feign) variant.
- **Key scope is the `execute(...)` call.** Reusing the returned `Mono` reuses the same key (same
  logical operation); a new operation means a new `execute(...)`.

## License

MIT. See [LICENSE](LICENSE).
