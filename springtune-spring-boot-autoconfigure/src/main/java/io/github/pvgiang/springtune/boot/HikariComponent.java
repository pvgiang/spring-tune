package io.github.pvgiang.springtune.boot;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.pvgiang.springtune.model.Knob;
import io.github.pvgiang.springtune.model.Recommendation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads a {@link HikariDataSource}. Never calls a setter. */
final class HikariComponent implements TunedComponent {

    private final HikariDataSource dataSource;
    private final TimerReader timers;
    private final SpringTuneProperties properties;
    private final Optional<Integer> budgetCap;
    private final SampleWindow<HikariSample> samples;

    HikariComponent(HikariDataSource dataSource, TimerReader timers, SpringTuneProperties properties,
            Optional<Integer> budgetCap) {
        this.dataSource = dataSource;
        this.timers = timers;
        this.properties = properties;
        this.budgetCap = budgetCap;
        Duration retention = max(properties.getHikari().getWindow(), properties.getHikari().getLowUtilizationWindow())
                .plus(properties.getSampling().getInterval());
        this.samples = new SampleWindow<>(retention);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    String poolName() {
        String name = dataSource.getPoolName();
        return name != null ? name : "default";
    }

    @Override
    public String id() {
        return "hikari:" + poolName();
    }

    private String knobId(String name) {
        return "hikari." + poolName() + "." + name;
    }

    @Override
    public List<Knob> knobs() {
        HikariConfigMXBean config = dataSource.getHikariConfigMXBean();
        String source = "HikariConfigMXBean";
        List<Knob> knobs = new ArrayList<>();
        knobs.add(new Knob(knobId("maximumPoolSize"), id(), "maximumPoolSize", config.getMaximumPoolSize(), 10L, 1L,
                budgetCap.map(Integer::longValue).orElse(null), "count", source));
        knobs.add(new Knob(knobId("minimumIdle"), id(), "minimumIdle", config.getMinimumIdle(), null, 0L, null,
                "count", source));
        knobs.add(new Knob(knobId("connectionTimeout"), id(), "connectionTimeout", config.getConnectionTimeout(),
                30_000L, 250L, null, "ms", source));
        knobs.add(new Knob(knobId("idleTimeout"), id(), "idleTimeout", config.getIdleTimeout(), 600_000L, null, null,
                "ms", source));
        knobs.add(new Knob(knobId("maxLifetime"), id(), "maxLifetime", config.getMaxLifetime(), 1_800_000L, 30_000L,
                null, "ms", source));
        knobs.add(new Knob(knobId("leakDetectionThreshold"), id(), "leakDetectionThreshold",
                config.getLeakDetectionThreshold(), 0L, null, null, "ms", source));
        return knobs;
    }

    @Override
    public Map<String, Object> signals() {
        Map<String, Object> signals = new LinkedHashMap<>();
        HikariSample latest = samples.latest();
        if (latest == null) {
            signals.put("status", "pool not started yet");
            return signals;
        }
        signals.put("sampledAt", latest.at().toString());
        signals.put("active", latest.active());
        signals.put("idle", latest.idle());
        signals.put("waitingThreads", latest.pending());
        signals.put("maximumPoolSize", latest.maximumPoolSize());
        signals.put("holdTimeMetrics", latest.timersAvailable() ? "available" : "unavailable");
        return signals;
    }

    @Override
    public int sampleCount() {
        return samples.size();
    }

    @Override
    public void sample(Instant now) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        Optional<TimerReader.Totals> acquire = timers.read("hikaricp.connections.acquire", poolName());
        Optional<TimerReader.Totals> usage = timers.read("hikaricp.connections.usage", poolName());
        boolean available = acquire.isPresent() && usage.isPresent();
        samples.add(new HikariSample(now, pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(), dataSource.getHikariConfigMXBean().getMaximumPoolSize(), available,
                acquire.map(TimerReader.Totals::count).orElse(0L), acquire.map(TimerReader.Totals::totalMs).orElse(0.0),
                usage.map(TimerReader.Totals::count).orElse(0L), usage.map(TimerReader.Totals::totalMs).orElse(0.0)));
    }

    @Override
    public List<Recommendation> recommend(Instant now) {
        SpringTuneProperties.Hikari settings = properties.getHikari();
        Duration tolerance = properties.getSampling().getInterval();
        int current = dataSource.getHikariConfigMXBean().getMaximumPoolSize();
        return HikariAdvisor.advise(knobId("maximumPoolSize"), current,
                samples.covering(now, settings.getWindow(), tolerance),
                samples.covering(now, settings.getLowUtilizationWindow(), tolerance),
                budgetCap, settings, now).map(List::of).orElse(List.of());
    }
}
