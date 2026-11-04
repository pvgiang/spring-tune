package io.github.winky.springtune.limiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code springtune.limiter.*}. The limiter is off unless {@code enabled=true}, and when enabled it only counts
 * would-be rejections (shadow mode) until {@code enforce=true}.
 */
@ConfigurationProperties("springtune.limiter")
public class LimiterProperties {

    /** Whether the adaptive concurrency limiter is installed. */
    private boolean enabled = false;

    /**
     * Whether requests over the limit are rejected. {@code false} (shadow mode) lets every request through and
     * counts the ones that would have been rejected as {@code springtune.limiter.requests{outcome=shadow_rejected}}.
     */
    private boolean enforce = false;

    /**
     * Limit algorithm. Vegas is the default because it held a stable limit under sustained overload in our load
     * tests, where Gradient2 drifted (see docs/limiter.md).
     */
    private Algorithm algorithm = Algorithm.VEGAS;

    /** Concurrency limit before the algorithm has learned anything. */
    private int initialLimit = 20;

    /** The limit never goes below this, whatever the algorithm computes. */
    private int minLimit = 10;

    /** The limit never goes above this. */
    private int maxLimit = 200;

    /**
     * Completed samples (successful or dropped requests) before anything is rejected, also in enforce mode.
     * Protects cold start and low-traffic instances while the algorithm has no latency baseline.
     */
    private long warmupSamples = 500;

    /** Path patterns ({@code PathPattern} syntax, relative to the context path) that are limited. */
    private List<String> includePaths = new ArrayList<>(List.of("/**"));

    /**
     * Path patterns that are never limited. Takes precedence over include-paths. The actuator base path
     * ({@code management.endpoints.web.base-path}) is always excluded as well.
     */
    private List<String> excludePaths = new ArrayList<>(List.of("/actuator/**"));

    /** HTTP status of a rejected request. */
    private int rejectStatus = 503;

    /** Value of the {@code Retry-After} header on a rejected request (whole seconds, rounded up; 0 omits it). */
    private Duration retryAfter = Duration.ofSeconds(1);

    public enum Algorithm {
        /** Netflix Gradient2: compares short-term with long-term average latency. */
        GRADIENT2,
        /** Netflix Vegas: compares latency with the minimum (no-load) latency seen. */
        VEGAS,
        /** Additive increase, multiplicative decrease on dropped (timed-out) requests only. */
        AIMD
    }

    /** Fails fast on settings that cannot work. */
    void validate() {
        if (minLimit < 1) {
            throw new IllegalStateException("springtune.limiter.min-limit must be at least 1 (is " + minLimit + ")");
        }
        if (minLimit > maxLimit) {
            throw new IllegalStateException("springtune.limiter.min-limit (" + minLimit
                    + ") must not be greater than springtune.limiter.max-limit (" + maxLimit + ")");
        }
        if (initialLimit < minLimit || initialLimit > maxLimit) {
            throw new IllegalStateException("springtune.limiter.initial-limit (" + initialLimit
                    + ") must be between min-limit (" + minLimit + ") and max-limit (" + maxLimit + ")");
        }
        if (warmupSamples < 0) {
            throw new IllegalStateException("springtune.limiter.warmup-samples must not be negative");
        }
        if (rejectStatus < 400 || rejectStatus > 599) {
            throw new IllegalStateException(
                    "springtune.limiter.reject-status must be a 4xx or 5xx status (is " + rejectStatus + ")");
        }
        if (retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalStateException("springtune.limiter.retry-after must not be negative");
        }
    }

    /** {@code Retry-After} in whole seconds, rounded up; 0 means no header. */
    long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds();
        return retryAfter.getNano() > 0 ? seconds + 1 : seconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
    }

    public int getInitialLimit() {
        return initialLimit;
    }

    public void setInitialLimit(int initialLimit) {
        this.initialLimit = initialLimit;
    }

    public int getMinLimit() {
        return minLimit;
    }

    public void setMinLimit(int minLimit) {
        this.minLimit = minLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public long getWarmupSamples() {
        return warmupSamples;
    }

    public void setWarmupSamples(long warmupSamples) {
        this.warmupSamples = warmupSamples;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public int getRejectStatus() {
        return rejectStatus;
    }

    public void setRejectStatus(int rejectStatus) {
        this.rejectStatus = rejectStatus;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }
}
