/**
 * Core of idem-client: key generation, the Reactor Context that carries the key, and the executor
 * that keeps it stable across retries.
 *
 * <p>Null-marked: unless a type use is explicitly annotated {@code @Nullable}, it is not null, on
 * arguments and on return values alike.
 */
@NullMarked
package io.github.mustafakemalv.idemclient.core;

import org.jspecify.annotations.NullMarked;
