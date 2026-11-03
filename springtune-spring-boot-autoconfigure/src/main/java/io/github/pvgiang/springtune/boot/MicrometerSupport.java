package io.github.pvgiang.springtune.boot;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ListableBeanFactory;

/** Micrometer integration. Only loaded when Micrometer is on the classpath. */
final class MicrometerSupport {

    private MicrometerSupport() {
    }

    static TimerReader timerReader(ListableBeanFactory beanFactory) {
        MeterRegistry registry = beanFactory.getBeanProvider(MeterRegistry.class).getIfAvailable();
        if (registry == null) {
            return TimerReader.NONE;
        }
        return (name, pool) -> {
            Timer timer = registry.find(name).tag("pool", pool).timer();
            return timer == null ? Optional.empty()
                    : Optional.of(new TimerReader.Totals(timer.count(), timer.totalTime(TimeUnit.MILLISECONDS)));
        };
    }

    /** {@code springtune.recommendations}: number of current recommendations. */
    static MeterBinder metrics(TuningService service) {
        return registry -> Gauge.builder("springtune.recommendations", service, s -> s.recommendations().size())
                .description("Current SpringTune recommendations (never applied automatically)")
                .register(registry);
    }
}
