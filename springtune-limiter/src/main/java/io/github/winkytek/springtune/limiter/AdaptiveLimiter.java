package io.github.winkytek.springtune.limiter;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.AIMDLimit;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.VegasLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Adaptive concurrency limiter for inbound requests. A thin facade over Netflix {@code SimpleLimiter}, so that
 * the dependency can be replaced or vendored without touching the filters.
 *
 * <p>Every request either is {@linkplain #bypass() bypassed} or calls {@link #acquire()} and, unless rejected,
 * {@linkplain Permit#release(Outcome) releases} its permit exactly once with an {@link Outcome}. In shadow mode,
 * and in enforce mode before {@code warmupSamples} completed samples, a request over the limit is let through
 * without a permit (it does not count against the limit and teaches the algorithm nothing) and is counted as
 * {@link Decision#SHADOW_REJECTED}.
 */
public final class AdaptiveLimiter {

    /** What happened to a request at the limiter. */
    public enum Decision {
        /** Under the limit; holds a permit. */
        ACCEPTED,
        /** Over the limit in enforce mode after warmup; must be answered with the reject status. */
        REJECTED,
        /** Over the limit, but let through (shadow mode or warmup). */
        SHADOW_REJECTED,
        /** Never limited (excluded path, actuator, management port, SSE, WebSocket). */
        BYPASSED
    }

    /** How a request that held a permit ended, as fed back to the limit algorithm. */
    public enum Outcome {
        /** Completed; its latency is a valid sample. */
        SUCCESS,
        /** Says nothing about capacity (5xx, exception, client cancel); permit returned without a sample. */
        IGNORE,
        /** Timed out; a strong overload signal (AIMD and Gradient2 back off). */
        DROPPED;

        /**
         * Status below 500 is a success. A 5xx is ignored: it is often a fast failure from a broken downstream
         * whose latency would wrongly tell the algorithm there is spare capacity.
         */
        public static Outcome forStatus(int status) {
            return status < 500 ? SUCCESS : IGNORE;
        }
    }

    private final SimpleLimiter<Void> limiter;

    private final boolean enforce;

    private final long warmupSamples;

    private final LongAdder completedSamples = new LongAdder();

    private final Map<Decision, LongAdder> counts = new EnumMap<>(Decision.class);

    private final Permit rejected = new Permit(Decision.REJECTED, null);

    private final Permit shadowRejected = new Permit(Decision.SHADOW_REJECTED, null);

    AdaptiveLimiter(Limit algorithm, int minLimit, int maxLimit, boolean enforce, long warmupSamples) {
        this.limiter = SimpleLimiter.newBuilder().named("springtune")
                .limit(new ClampedLimit(algorithm, minLimit, maxLimit)).build();
        this.enforce = enforce;
        this.warmupSamples = warmupSamples;
        for (Decision decision : Decision.values()) {
            counts.put(decision, new LongAdder());
        }
    }

    /** Builds the limiter from validated properties. */
    public static AdaptiveLimiter create(LimiterProperties properties) {
        properties.validate();
        return new AdaptiveLimiter(algorithm(properties), properties.getMinLimit(), properties.getMaxLimit(),
                properties.isEnforce(), properties.getWarmupSamples());
    }

    static Limit algorithm(LimiterProperties properties) {
        int initial = properties.getInitialLimit();
        int min = properties.getMinLimit();
        int max = properties.getMaxLimit();
        return switch (properties.getAlgorithm()) {
            case GRADIENT2 -> Gradient2Limit.newBuilder().initialLimit(initial).minLimit(min).maxConcurrency(max)
                    .build();
            // Vegas has no minimum; ClampedLimit applies min-limit to it (and to the others).
            case VEGAS -> VegasLimit.newBuilder().initialLimit(initial).maxConcurrency(max).build();
            case AIMD -> AIMDLimit.newBuilder().initialLimit(initial).minLimit(min).maxLimit(max).build();
        };
    }

    /** Asks for a permit. Never blocks. A {@link Decision#REJECTED} permit must not be used to serve the request. */
    public Permit acquire() {
        Optional<Limiter.Listener> listener = limiter.acquire(null);
        if (listener.isPresent()) {
            counts.get(Decision.ACCEPTED).increment();
            return new Permit(Decision.ACCEPTED, listener.get());
        }
        if (enforce && isWarmedUp()) {
            counts.get(Decision.REJECTED).increment();
            return rejected;
        }
        counts.get(Decision.SHADOW_REJECTED).increment();
        return shadowRejected;
    }

    /** Records a request that is never limited. */
    public void bypass() {
        counts.get(Decision.BYPASSED).increment();
    }

    /** Current concurrency limit. */
    public int limit() {
        return limiter.getLimit();
    }

    /** Permits currently held. */
    public int inflight() {
        return limiter.getInflight();
    }

    /** Whether requests over the limit are rejected (after warmup). */
    public boolean isEnforcing() {
        return enforce;
    }

    /** Whether enough samples completed for rejections to be allowed. */
    public boolean isWarmedUp() {
        return completedSamples.sum() >= warmupSamples;
    }

    /** Completed samples fed to the algorithm (successful and dropped requests). */
    public long completedSamples() {
        return completedSamples.sum();
    }

    /** Requests seen with the given decision since startup. */
    public long count(Decision decision) {
        return counts.get(decision).sum();
    }

    /** One request's permit. Releasing is idempotent: only the first {@link #release(Outcome)} counts. */
    public final class Permit {

        private final Decision decision;

        private final Limiter.Listener listener;

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Decision decision, Limiter.Listener listener) {
            this.decision = decision;
            this.listener = listener;
        }

        public Decision decision() {
            return decision;
        }

        public boolean isRejected() {
            return decision == Decision.REJECTED;
        }

        /** Returns the permit and feeds the outcome to the algorithm. A no-op without a permit or when repeated. */
        public void release(Outcome outcome) {
            if (listener == null || !released.compareAndSet(false, true)) {
                return;
            }
            switch (outcome) {
                case SUCCESS -> listener.onSuccess();
                case DROPPED -> listener.onDropped();
                case IGNORE -> listener.onIgnore();
            }
            if (outcome != Outcome.IGNORE) {
                completedSamples.increment();
            }
        }
    }

    /** Keeps whatever the algorithm computes within [min, max]. */
    static final class ClampedLimit implements Limit {

        private final Limit delegate;

        private final int min;

        private final int max;

        ClampedLimit(Limit delegate, int min, int max) {
            this.delegate = delegate;
            this.min = min;
            this.max = max;
        }

        private int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }

        @Override
        public int getLimit() {
            return clamp(delegate.getLimit());
        }

        @Override
        public void notifyOnChange(Consumer<Integer> consumer) {
            delegate.notifyOnChange(value -> consumer.accept(clamp(value)));
        }

        @Override
        public void onSample(long startTime, long rtt, int inflight, boolean didDrop) {
            delegate.onSample(startTime, rtt, inflight, didDrop);
        }
    }
}
