package io.github.mustafakemalv.idemclient.autoconfigure;

import io.github.mustafakemalv.idemclient.web.IdempotencyKeyExchangeFilter;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code idem-client.*} configuration. */
@ConfigurationProperties(prefix = "idem-client")
public class IdempotencyProperties {

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
}
