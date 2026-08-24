# How it works

idem-client keeps one **idempotency key** stable across every retry of a single logical operation, so
a downstream that honors `Idempotency-Key` sees the retries as the same request and does not process
them twice.

## 1. The problem: ThreadLocal dies on the reactive thread-hop

In a blocking app one request runs on one thread start to finish, so request-scoped data (a user id,
a trace id) can live in a `ThreadLocal`. A reactive chain is different: the work hops threads (the
thread that starts a call is not necessarily the one that resumes it when the response arrives). A
`ThreadLocal` set on the first thread is gone on the next, so it cannot carry an idempotency key
reliably.

## 2. The fix: the Reactor Context

The Reactor Context is bound to the **subscription**, not to a thread, so it travels with the chain
across thread hops. idem-client writes the key with `contextWrite` and reads it back with
`Mono.deferContextual`; because the Context flows with the subscription, the write and the read find
each other no matter which thread runs them.

One subtlety: `contextWrite` propagates **upward** (a write low in the chain is visible to operators
above it), because the subscribe signal travels from the bottom of the chain up to the source.

## 3. Why the key survives a retry

`retryWhen` **resubscribes** the chain on failure. Since the Context is bound to the subscription and
the resubscribe reuses it, every attempt sees the same key. The key is generated **once**, outside
the retry, and placed in the Context as a constant:

    operation
        .retryWhen(retrySpec)
        .contextWrite(ctx -> IdempotencyContext.withKey(ctx, key));  // key is fixed, generated once

Generating the key *inside* `contextWrite` would be a bug: each resubscribe would run it again and
mint a new key on every retry, defeating the whole point. That is the footgun idem-client is built to
avoid, and the end-to-end tests assert that all attempts carry the same key.

## 4. Stamping the header

`IdempotencyKeyExchangeFilter` is a `WebClient` `ExchangeFilterFunction`. On each outbound request it
reads the key from the Context (via `deferContextual`) and adds the `Idempotency-Key` header. If the
Context has no key, or the request already sets the header explicitly, the request passes through
unchanged.

## 5. Honest scope

idem-client guarantees only that retries of one operation carry a **stable** key. It does not make
the downstream idempotent; that is the downstream's job. If the downstream ignores the header, there
is no protection. This is an at-most-once *effect*, not exactly-once delivery.
