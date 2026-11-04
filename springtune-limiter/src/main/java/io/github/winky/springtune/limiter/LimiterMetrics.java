package io.github.winky.springtune.limiter;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Decision;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;

/**
 * Micrometer meters: gauges {@code springtune.limiter.limit} and {@code springtune.limiter.inflight}, counter
 * {@code springtune.limiter.requests{outcome=accepted|rejected|shadow_rejected|bypassed}}.
 */
final class LimiterMetrics implements MeterBinder {

    private final AdaptiveLimiter limiter;

    LimiterMetrics(AdaptiveLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("springtune.limiter.limit", limiter, AdaptiveLimiter::limit)
                .description("Current adaptive concurrency limit").register(registry);
        Gauge.builder("springtune.limiter.inflight", limiter, AdaptiveLimiter::inflight)
                .description("Requests currently holding a limiter permit").register(registry);
        for (Decision decision : Decision.values()) {
            FunctionCounter.builder("springtune.limiter.requests", limiter, l -> l.count(decision))
                    .description("Requests seen by the adaptive limiter, by decision")
                    .tag("outcome", decision.name().toLowerCase(Locale.ROOT)).register(registry);
        }
    }
}
