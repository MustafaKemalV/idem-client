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
- **Transient-only retries:** an allow-list, not a deny-list. 5xx, 429 and genuine transport failures
  are retried, including a connection that dies mid-response; a deterministic 4xx, and anything raised
  on your own side of the exchange, are not.
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

A key you supply is validated before anything is sent: not blank, at most 255 characters, printable
US-ASCII only. Blank is the one worth calling out, because it is a legal HTTP header value, so
without the check an empty key would go out as an empty `Idempotency-Key`, be read downstream as no
key at all, and leave you with no protection and nothing in the log to notice. The same rules apply
to a key from your own `IdempotencyKeyGenerator`.

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

The auto-configured `IdempotentExecutor` retries transient failures only, up to `max-attempts`, with
exponential backoff and jitter capped at `max-backoff`. What counts as transient is an **allow-list**:

| Retried | Not retried |
| --- | --- |
| HTTP 5xx | Deterministic 4xx: 400, 401, 403, 404, 409, 422 and the rest |
| Transport failures: connection reset, premature close, DNS and TLS failures (`WebClientRequestException`, `IOException`) | Anything raised on your own side of the exchange: a `NullPointerException` in your `map`, a decoding failure, a cancelled subscription |
| The four 4xx codes the specs call retryable: 408 Request Timeout, 421 Misdirected Request, 425 Too Early, 429 Too Many Requests | |
| The per-attempt timeout (`TimeoutException`) | |
| A connection that dies **mid-response**, after the status line arrived | |

That last row is the case this library is built for, and it is the easiest one to miss. Once the
status has been read, a transport failure while reading the *body* is reported as a
`WebClientResponseException` carrying the response status (typically 200) with an `IOException` cause.
The request was dispatched, the downstream may well have processed it, and you never learned the
outcome. Retrying it is safe only because the key is stable.

An allow-list has a cost, and it is worth stating: if you map a 5xx to your own exception type with
`onStatus`, the predicate can no longer see a status and will not retry it either. Compose rather than
replace:

    Retry retry = Retry.backoff(3, Duration.ofMillis(100))
            .filter(t -> IdemClientAutoConfiguration.isRetryable(t) || t instanceof MyRetryableException);

When retries are exhausted the original error is propagated (not wrapped in a `RetryExhaustedException`).
To change any of this, define your own `IdempotentExecutor` (or `Retry`) bean; every auto-configured
bean backs off when you provide your own.

Set `per-attempt-timeout` (and your WebClient's `responseTimeout`) to bound a slow downstream: a
timed-out attempt is retried safely precisely because the key stays stable.

### Bring your own retry, carefully

The key is minted per subscription, and that is what makes a fan-out correct: two subscriptions are
two logical operations and must not share a key. The same rule bites in the other direction if you
stack your own retry ABOVE `execute(...)`, because every attempt resubscribes and mints a new key:

    // WRONG: four attempts, four DIFFERENT keys. The downstream sees four unrelated operations and,
    // being perfectly idempotent, charges four times.
    client.execute(wc -> wc.post().uri("/charge").bodyValue(request)
                    .retrieve().bodyToMono(Receipt.class))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(200)));

    // RIGHT: let the executor own the retry, so every attempt is one subscription under one key.
    client.execute(wc -> wc.post().uri("/charge").bodyValue(request)
                    .retrieve().bodyToMono(Receipt.class));

    // ALSO RIGHT: supply the key yourself and it survives whoever resubscribes.
    client.execute(orderId, wc -> wc.post().uri("/charge").bodyValue(request)
                    .retrieve().bodyToMono(Receipt.class))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(200)));

The same applies to a Resilience4j `RetryOperator`, a gateway wrapper, or anything else that
resubscribes. `onKeyMinted` firing repeatedly for one logical operation is the programmatic signal,
and the library logs a WARN when a single `execute(...)` is subscribed more than once.

This is deliberately not prevented in code. The obvious structural fix, reusing a key already present
in the Reactor Context, would make a nested `execute(B)` inside `execute(A)` inherit A's key; the
downstream would deduplicate B as a replay of A, and B's payment would vanish without a trace. A
visible double charge is bad, a silent lost payment is worse, so the footgun is made loud rather than
traded for a quieter one.

The default backoff does not read the failing response, so it ignores a `Retry-After` header on 429/503.
To honor it, override the `IdempotentExecutor` bean with a custom `Retry` that reads the header
(`IdemClientAutoConfiguration.isRetryable(...)` is public, so you can reuse the transient-only policy):

    Retry retry = Retry.from(signals -> signals.flatMap(rs -> {
        if (rs.totalRetries() >= 3 || !IdemClientAutoConfiguration.isRetryable(rs.failure())) {
            return Mono.error(rs.failure());
        }
        String retryAfter = rs.failure() instanceof WebClientResponseException e
                ? e.getHeaders().getFirst("Retry-After") : null;
        Duration delay = retryAfter != null
                ? Duration.ofSeconds(Long.parseLong(retryAfter.trim()))
                : Duration.ofMillis(200);
        return Mono.delay(delay).thenReturn(rs.totalRetries());
    }));

## Observability

The library requires no observability dependency. Implement `IdempotencyListener` as a bean to feed
any metrics or tracing system; it replaces the no-op default:

    @Bean
    IdempotencyListener idempotencyListener(MeterRegistry registry) {
        return new IdempotencyListener() {
            @Override public void onKeyMinted(String key) {
                log.info("idempotency key {} minted", key);
            }
            @Override public void onRetry(String key, long attempt) {
                registry.counter("idem.retries").increment();
            }
            @Override public void onFailed(String key, long attempts, Throwable error) {
                registry.counter("idem.failed").increment();
                if (attempts > 1) {
                    log.warn("operation under key {} failed after {} attempts: outcome UNKNOWN, reconcile",
                            key, attempts);
                }
            }
        };
    }

Every callback carries the key, and that is the reason the hook exists. A failure after more than one
attempt is not a failure you can treat as "nothing happened": the request reached the wire, the
downstream may have processed it, and the response never came back. The only way out is to ask the
downstream what happened to that key, so the key has to be recoverable. A generated key lives inside
one subscription, so `onKeyMinted` is the only place it becomes visible to you.

`onKeyMinted` firing more than once for what you believe is one logical operation is also the
signature of the footgun described under [Bring your own retry](#bring-your-own-retry-carefully).

The filter also logs at DEBUG when it stamps a key (truncated in the log). For distributed tracing,
read the key from the Reactor Context inside your own chain, with `Mono.deferContextual` and
`IdempotencyContext.keyFrom(...)`, and attach it as a span tag. Reactor's automatic context
propagation (`Hooks.enableAutomaticContextPropagation()`) neither helps nor is needed here: it copies
values only for registered `ThreadLocalAccessor`s, and this library registers none, precisely because
the key must never depend on a ThreadLocal that the reactive thread-hop would lose.

## Guarding against key reuse

Reusing an idempotency key with a DIFFERENT request is a classic bug (providers like Stripe reject it
with a 400). Pass a fingerprint of the request alongside an explicit key, and the library fails fast,
locally, before sending, if the same key is later used with a different fingerprint:

    client.execute(orderId, fingerprintOf(request),
            wc -> wc.post().uri("/charge").bodyValue(request).retrieve().bodyToMono(Receipt.class));

You compute the fingerprint (for example a hash of the body and amount); the library does not buffer
the reactive body, and stores a SHA-256 digest rather than the value you pass, so a serialized body
is not kept in the heap verbatim. Digesting is not encryption, though: pass something low-entropy and
the digest is still a commitment to it, so hash the body yourself before handing it over if it
carries card data.

The guard is an in-memory, bounded, per-process LRU, so it catches a local mistake, not a
cross-process conflict. It is not scoped by downstream, on purpose: a key identifies one logical
operation, so charging at a processor and recording in a ledger are two operations that want two
keys, even though they are one business event, and a per-client scope would have hidden exactly that
mistake. If you deliberately send one key to two downstreams, use `execute(key, call)` without a
fingerprint. When the LRU fills it forgets its oldest keys and says so once in the log: a forgotten
key reused with a different request is no longer caught.

## How it works

See [docs/how-it-works.md](docs/how-it-works.md) for the Reactor Context mechanics and why the key
survives a retry.

## Limitations

- **Not exactly-once.** It is an at-most-once *effect*, only as strong as the downstream's own
  idempotency.
- **The downstream must honor the header.** If it ignores `Idempotency-Key`, there is no protection.
- **Reactive only.** v1 targets `WebClient`; there is no blocking (RestTemplate/Feign) variant.
- **Your operation must be safely re-subscribable.** A retry resubscribes, so the request is sent
  again from the same definition. `bodyValue` and `fromValue` are fine. A one-shot streaming body (a
  `Flux<DataBuffer>` read from a file or an input stream) is not: the second attempt sends an empty or
  partial body under the SAME key. Nothing catches this for you, the fingerprint guard included, since
  it runs once per operation and never sees the retry.
- **Key scope is one subscription.** Each subscription of a returned `Mono` gets its own key; a retry
  of that subscription keeps the same key. An explicit key must be unique per logical operation.
- **One `execute(...)` is one logical operation, and one request.** The key is written for the whole
  subscription, so *every* request made inside a single `execute(...)` carries it. Chain two different
  calls in one `execute` and they share a key, which entitles the downstream to treat the second as a
  replay of the first and return the first response instead of performing it, so the second operation
  silently never happens. Give each operation its own `execute(...)`. The library logs a WARN when one
  key is stamped on two different requests.

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

Or derive a stable key from your business identity with the built-in helper:

    idempotency.execute(IdempotencyKeys.of("charge", orderId), wc -> wc.post().uri("/charge")...);

`IdempotencyKeys.of` gives you a stable, well-formed key. It does **not** keep the input secret: the
encoding is documented, and a business identity comes from a small, enumerable space, so anyone
holding the key can try candidates until one matches. Do not feed it a card number. Where the key
must also be unguessable, use the keyed variant:

    idempotency.execute(IdempotencyKeys.hmac(secret, "charge", orderId), wc -> ...);

Treat that secret as a signing key. It must outlive the operation and be identical everywhere the
operation can be replayed, because rotating it changes every key, and a replay after a crash would
then look like a brand-new operation: the double charge walks back in through the feature meant to
prevent it.

That way a replay after a crash reuses the same key and the downstream deduplicates it. Genuine
cross-process durability (a persisted key + response store) is intentionally out of scope for this
transport-only library.

## Roadmap: caller-side idempotency store (design preview)

Today idem-client keeps a key stable across retries and delegates deduplication to the downstream. A
natural next direction is a caller-side store so a repeated logical operation (double-submit, queue
replay, restart) can short-circuit locally instead of re-issuing the side effect. The
`IdempotencyStore` SPI sketches the shape (in-flight / completed / failed, single-flight via `begin`),
so a durable backend (Redis, JDBC) could plug in without breaking the core API.

It is a design preview, not wired into the execution path in this release, and deliberately so: a
store only helps with a stable/deterministic key (not the random-UUID default), and even a durable
store cannot deliver exactly-once (the "committed downstream but not yet persisted here" crash window
is not closable on the caller side). Shipping the SPI without over-claiming keeps the library honest
and lean while marking the intended evolution.

## Compatibility

The public API is everything under `io.github.mustafakemalv.idemclient` that is `public`, minus
anything the javadoc marks as a design preview (today that is `IdempotencyStore`). Every public type
carries `@since`.

Within a `0.x` line, a patch release never breaks binary compatibility, and a minor release may,
which is what `0.x` means; each break is listed in the [changelog](CHANGELOG.md) with what to do
instead. From `1.0.0` onward, binary compatibility is kept within a major version. The jar declares
`Automatic-Module-Name: io.github.mustafakemalv.idemclient`, so that name is stable even if the
library adopts a real module descriptor later.

The wire behaviour is part of the contract too, and one piece deserves naming: the encoding behind
`IdempotencyKeys.of` and `IdempotencyKeys.hmac`. Callers persist those keys and replay them after a
crash, so changing the encoding would silently invalidate keys already written down. It is pinned by
tests and will not change within a major version.

## License

MIT. See [LICENSE](LICENSE).
