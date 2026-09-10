# Changelog

Notable changes to idem-client. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [semantic versioning](https://semver.org/), with the compatibility rules stated
in the [README](README.md#compatibility).

## [0.1.0] - unreleased

First release. Caller-side idempotency for outbound Spring WebFlux calls: a stable `Idempotency-Key`
that survives a reactive retry, so an idempotent downstream does not double-process a retried request.

### Added

- `IdempotentExecutor`: runs a reactive operation under one idempotency key, generated once per
  logical operation and carried in the Reactor Context so every retry attempt reuses it.
- `IdempotencyKeyExchangeFilter`: stamps the key on outbound `WebClient` requests, reading it from the
  Context rather than a `ThreadLocal`, which the reactive thread-hop would lose.
- `IdempotentWebClient` and `IdempotentWebClientFactory`: a `WebClient` with the filter already
  attached, so a call cannot silently go out without the key.
- Spring Boot auto-configuration, tunable with `idem-client.*` properties, every bean
  `@ConditionalOnMissingBean`.
- `IdempotencyKeys.of` and `IdempotencyKeys.hmac`: deterministic keys derived from a business identity,
  for retries that must survive a restart or a queue replay. `hmac` adds a secret where the key must
  also be unguessable.
- `KeyFingerprintGuard`: catches the same key reused with a different request, locally, before sending.
  Stores only a digest, scoped per client, and reports in the log when its LRU starts forgetting.
- `IdempotencyListener`: observability hook. Every event carries the key, which is what makes
  reconciliation possible after an ambiguous failure.
- `IdempotencyStore`: an SPI shipped as a design preview, deliberately not wired into the execution
  path. Not part of the public API surface for compatibility purposes.
- A per-attempt timeout, so one slow attempt cannot hold the whole operation.

### Behaviour worth knowing

- Retries are an allow-list of transient failures: 5xx, 429, transport failures, the per-attempt
  timeout, and a connection that dies mid-response after the status line arrived. Nothing else, which
  includes errors raised on the caller's own side of the exchange.
- The key is minted per subscription. A retry stacked *above* `execute(...)` therefore mints a new key
  per attempt; see [Bring your own retry, carefully](README.md#bring-your-own-retry-carefully).
- One `execute(...)` is one logical operation. Two different requests inside one call share a key, and
  the library warns when it sees that.
