# Security policy

## Reporting a vulnerability

Report privately through GitHub's [security advisory
form](https://github.com/MustafaKemalV/idem-client/security/advisories/new). Please do not open a
public issue for a vulnerability.

Include what the issue lets an attacker do, the affected version, and the smallest reproduction you
have. You will get an acknowledgement within a week. This is a small library maintained by one
person, so please read the scope below before reporting: several things that look like weaknesses
are documented properties of what this library deliberately does not do.

## Supported versions

The latest released version. There is no long-term support branch.

## What is in scope

- The idempotency key reaching the wire malformed, or a key crossing between logical operations.
- A key ending up somewhere it should not: a log line, an exception message, an error response.
- The retry policy re-sending a request it should not, or failing to re-send one whose outcome is
  genuinely unknown.
- Anything the library retains in memory that it should not, or that grows without a bound.

## What is out of scope

These are documented design boundaries, not defects:

- **The downstream ignoring the header.** The library generates and carries the key; deduplication is
  the downstream's job. If it does not honour `Idempotency-Key`, there is no protection, and that is
  stated plainly in the README.
- **`IdempotencyKeys.of` being reversible for a low-entropy input.** It is a documented SHA-256 over a
  documented encoding. An order id or a card number can be recovered from the digest by trying
  candidates; this is why the javadoc says not to feed it a card number, and why `IdempotencyKeys.hmac`
  exists for keys that must also be unguessable.
- **A caller's own secret management** for `IdempotencyKeys.hmac`. The library never stores, logs or
  transports the secret.
- **A key minted per subscription not surviving a crash.** Documented under "Durability boundary": a
  crash-safe key must be derived from the business identity and persisted by the caller.
