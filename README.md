# idem-client

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

## Requirements

- Java 25
- Spring Boot 4.1 (Spring WebFlux / `WebClient`)

## Status

🚧 v1 in progress.

## License

MIT. See [LICENSE](LICENSE).
