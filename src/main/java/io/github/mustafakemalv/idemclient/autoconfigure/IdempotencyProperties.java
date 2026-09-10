package io.github.mustafakemalv.idemclient.autoconfigure;

import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code idem-client.*} configuration.
 *
 * <p>Validation lives here rather than in the bean that consumes these values, so that it runs
 * whenever the properties are bound, independently of which auto-configured beans an application has
 * replaced with its own. It uses no bean-validation API on purpose: this library ships no validator,
 * and {@code @Validated} without one on the classpath is a silent no-op, which would be worse than the
 * loud failure it replaced.
 */
@ConfigurationProperties(prefix = "idem-client")
public class IdempotencyProperties implements InitializingBean {

    /** Whether idem-client auto-configuration is active. */
    private boolean enabled = true;

    /** Name of the header carrying the idempotency key on outbound requests. */
    private String headerName = IdempotencyKeyExchangeFilter.DEFAULT_HEADER_NAME;

    /** Maximum number of retry attempts, in addition to the initial call. */
    private long maxAttempts = 3;

    /** Minimum (exponential) backoff between retry attempts. */
    private Duration minBackoff = Duration.ofMillis(100);

    /** Maximum (exponential) backoff between retry attempts. */
    private Duration maxBackoff = Duration.ofSeconds(2);

    /** Per-attempt timeout; a timed-out attempt is retried as a transport error. Null = no timeout. */
    private Duration perAttemptTimeout;

    @Override
    public void afterPropertiesSet() {
        if (maxAttempts < 0) {
            throw new IllegalStateException("idem-client.max-attempts must be >= 0");
        }
        if (minBackoff.isNegative() || maxBackoff.isNegative()) {
            throw new IllegalStateException("idem-client.min-backoff and max-backoff must not be negative");
        }
        if (maxBackoff.compareTo(minBackoff) < 0) {
            throw new IllegalStateException("idem-client.max-backoff must be >= min-backoff");
        }
        if (perAttemptTimeout != null && (perAttemptTimeout.isNegative() || perAttemptTimeout.isZero())) {
            throw new IllegalStateException("idem-client.per-attempt-timeout must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public long getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(long maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getMinBackoff() {
        return minBackoff;
    }

    public void setMinBackoff(Duration minBackoff) {
        this.minBackoff = minBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public Duration getPerAttemptTimeout() {
        return perAttemptTimeout;
    }

    public void setPerAttemptTimeout(Duration perAttemptTimeout) {
        this.perAttemptTimeout = perAttemptTimeout;
    }
}
