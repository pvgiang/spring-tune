package io.github.winkytek.springtune.boot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationListener;

/** Micrometer integration. Only loaded when Micrometer is on the classpath. */
final class MicrometerSupport {

    private MicrometerSupport() {
    }

    static TimerReader timerReader(ListableBeanFactory beanFactory) {
        MeterRegistry configured = beanFactory.getBeanProvider(MeterRegistry.class).getIfAvailable();
        MeterRegistry registry = configured == null ? null : cumulative(configured);
        if (registry == null) {
            return TimerReader.NONE;
        }
        return new TimerReader() {
            @Override
            public Optional<Totals> read(String name, String pool) {
                Timer timer = registry.find(name).tag("pool", pool).timer();
                return timer == null ? Optional.empty()
                        : Optional.of(new Totals(timer.count(), timer.totalTime(TimeUnit.MILLISECONDS)));
            }

            @Override
            public Optional<Double> counter(String name, String pool) {
                Counter counter = registry.find(name).tag("pool", pool).counter();
                return counter == null ? Optional.empty() : Optional.of(counter.count());
            }

            @Override
            public Optional<HttpTotals> http() {
                long requests = 0;
                long errors = 0;
                double totalMs = 0;
                boolean found = false;
                for (Timer timer : registry.find("http.server.requests").timers()) {
                    found = true;
                    requests += timer.count();
                    totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
                    String status = timer.getId().getTag("status");
                    if (status != null && status.startsWith("5")) {
                        errors += timer.count();
                    }
                }
                return found ? Optional.of(new HttpTotals(requests, totalMs, errors)) : Optional.empty();
            }
        };
    }

    /**
     * The registry to read cumulative totals from: the registry itself unless it is a step registry, or the
     * first cumulative child of a composite. {@code null} when there is none (only step registries): deltas of
     * step values would be meaningless, so hold-time metrics are then reported as unavailable.
     */
    static MeterRegistry cumulative(MeterRegistry registry) {
        if (registry instanceof CompositeMeterRegistry composite) {
            for (MeterRegistry child : composite.getRegistries()) {
                MeterRegistry found = cumulative(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return registry instanceof StepMeterRegistry ? null : registry;
    }

    /** {@code springtune.recommendations}: number of current recommendations. */
    static MeterBinder metrics(TuningService service) {
        return registry -> Gauge.builder("springtune.recommendations", service, s -> s.recommendations().size())
                .description("Current SpringTune recommendations (applied only through apply mode)")
                .register(registry);
    }

    /** {@code springtune.changes{outcome}}: apply-mode changes by final outcome (and refusals). */
    static final class ChangeMetrics implements MeterBinder, ApplicationListener<SpringTuneChangeEvent> {

        private volatile MeterRegistry registry;

        @Override
        public void bindTo(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void onApplicationEvent(SpringTuneChangeEvent event) {
            MeterRegistry target = registry;
            ChangeAuditEntry entry = event.getEntry();
            if (target != null && entry.outcome().isFinal()) {
                Counter.builder("springtune.changes").tag("outcome", entry.outcome().name().toLowerCase(java.util.Locale.ROOT))
                        .description("SpringTune apply-mode changes by outcome").register(target).increment();
            }
        }
    }
}
