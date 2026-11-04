package io.github.winky.springtune.limiter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;

/**
 * Server-side view from Boot's own {@code http.server.requests} timer (the observation filter runs outside the
 * limiter, so rejected requests are recorded too). Needs
 * {@code management.metrics.distribution.percentiles.http.server.requests=0.5,0.99}.
 */
record ServerStats(long ok, double okP50Millis, double okP99Millis, long rejected) {

    static final String PERCENTILES = "management.metrics.distribution.percentiles.http.server.requests=0.5,0.99";

    static ServerStats of(ApplicationContext context, String uri) {
        MeterRegistry registry = context.getBean(MeterRegistry.class);
        Timer ok = registry.find("http.server.requests").tag("uri", uri).tag("status", "200").timer();
        // A rejected request never reaches a handler, so its uri tag is not the handler's pattern: count all 503s.
        long rejected = registry.find("http.server.requests").tag("status", "503").timers().stream()
                .mapToLong(Timer::count).sum();
        double p50 = -1;
        double p99 = -1;
        if (ok != null) {
            for (ValueAtPercentile percentile : ok.takeSnapshot().percentileValues()) {
                if (percentile.percentile() == 0.5) {
                    p50 = percentile.value(TimeUnit.MILLISECONDS);
                } else if (percentile.percentile() == 0.99) {
                    p99 = percentile.value(TimeUnit.MILLISECONDS);
                }
            }
        }
        return new ServerStats(ok != null ? ok.count() : 0, p50, p99, rejected);
    }

    /** Forgets what was recorded so far (e.g. a warm-up phase); the timers are re-created on the next request. */
    static void reset(ApplicationContext context) {
        MeterRegistry registry = context.getBean(MeterRegistry.class);
        registry.find("http.server.requests").meters().forEach(registry::remove);
    }

    String summary() {
        return String.format(Locale.ROOT, "server-side http.server.requests: 200 x%d p50=%.0fms p99=%.0fms, 503 x%d",
                ok, okP50Millis, okP99Millis, rejected);
    }
}
