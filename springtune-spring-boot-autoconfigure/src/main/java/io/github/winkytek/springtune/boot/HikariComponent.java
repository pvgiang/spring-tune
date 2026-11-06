package io.github.winkytek.springtune.boot;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a {@link HikariDataSource}. Never calls a setter: in apply mode the only write path is the
 * {@link HikariPoolWriter} of its {@link #writableKnob() writable knob}.
 */
final class HikariComponent implements TunedComponent {

    private final String beanName;
    private final HikariDataSource dataSource;
    private final TimerReader timers;
    private final SpringTuneProperties properties;
    private final Optional<Integer> budgetCap;
    private final SampleWindow<HikariSample> samples;
    private volatile WritableKnob writableKnob;
    private volatile ServerConnectionLimit serverLimit;
    private final java.util.concurrent.atomic.AtomicBoolean readingServerLimit =
            new java.util.concurrent.atomic.AtomicBoolean();

    HikariComponent(String beanName, HikariDataSource dataSource, TimerReader timers, SpringTuneProperties properties,
            Optional<Integer> budgetCap) {
        this.beanName = beanName;
        this.dataSource = dataSource;
        this.timers = timers;
        this.properties = properties;
        this.budgetCap = budgetCap;
        Duration retention = max(properties.getHikari().getWindow(), properties.getHikari().getLowUtilizationWindow())
                .plus(properties.getSampling().getInterval());
        this.samples = new SampleWindow<>(retention);
        this.serverLimit = budgetCap.isPresent() ? ServerConnectionLimit.notRead("the pool has not"
                + " started yet") : ServerConnectionLimit.notApplicable();
    }

    /** The server's connection limit as last read (see {@link #sample(Instant)}). */
    ServerConnectionLimit serverLimit() {
        return serverLimit;
    }

    /**
     * {@code maximumPoolSize} as apply mode sees it: writer, startup value and this pool's own counters, or
     * {@code null} until the pool has started (Hikari starts lazily and fills in defaults only then). The
     * startup value is the one seen the first time the started pool is inspected.
     */
    WritableKnob writableKnob() {
        WritableKnob knob = writableKnob;
        if (knob == null && dataSource.getHikariPoolMXBean() != null) {
            knob = new WritableKnob(knobId("maximumPoolSize"), id(), WritableKnob.Kind.HIKARI,
                    dataSource.getHikariConfigMXBean().getMaximumPoolSize(),
                    new HikariPoolWriter(dataSource::getHikariConfigMXBean), this::counters, this::serverLimit);
            writableKnob = knob;
        }
        return knob;
    }

    /** Cumulative counters of this pool (verification signals R1-R3) plus HTTP totals as evidence. */
    Map<String, Double> counters() {
        Map<String, Double> counters = new LinkedHashMap<>();
        timers.read("hikaricp.connections.acquire", poolName()).ifPresent(t -> {
            counters.put(Verification.ACQUIRES, (double) t.count());
            counters.put(Verification.ACQUIRE_MS, t.totalMs());
            counters.put(Verification.TIMEOUTS,
                    timers.counter("hikaricp.connections.timeout", poolName()).orElse(0.0));
        });
        timers.read("hikaricp.connections.usage", poolName()).ifPresent(t -> {
            counters.put(Verification.USAGES, (double) t.count());
            counters.put(Verification.USAGE_MS, t.totalMs());
        });
        if (!counters.isEmpty()) {
            timers.http().ifPresent(http -> {
                counters.put(Verification.HTTP_REQUESTS, (double) http.requests());
                counters.put(Verification.HTTP_MS, http.totalMs());
                counters.put(Verification.HTTP_5XX, (double) http.serverErrors());
            });
        }
        return counters;
    }

    @Override
    public Optional<ApplyPlan> applyPlan(Recommendation recommendation) {
        if (!recommendation.knobId().equals(knobId("maximumPoolSize"))) {
            return Optional.empty();
        }
        return ApplyPlans.hikari(recommendation, beanName, poolName(),
                dataSource.getHikariConfigMXBean().getMinimumIdle(), properties.getBudget(), budgetCap, serverLimit);
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
        ServerConnectionLimit server = serverLimit;
        if (server.status() != ServerConnectionLimit.Status.NOT_APPLICABLE) {
            signals.put("serverConnectionLimit", server.known() ? server.limit() + " (" + server.detail() + ")"
                    : server.status() + ": " + server.detail());
        }
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
        int idle = pool.getIdleConnections();
        samples.add(new HikariSample(now, pool.getActiveConnections(), idle,
                pool.getThreadsAwaitingConnection(), dataSource.getHikariConfigMXBean().getMaximumPoolSize(), available,
                acquire.map(TimerReader.Totals::count).orElse(0L), acquire.map(TimerReader.Totals::totalMs).orElse(0.0),
                usage.map(TimerReader.Totals::count).orElse(0L), usage.map(TimerReader.Totals::totalMs).orElse(0.0),
                timers.counter("hikaricp.connections.timeout", poolName()).orElse(0.0)));
        if (serverLimit.status() == ServerConnectionLimit.Status.NOT_READ
                && readingServerLimit.compareAndSet(false, true)) {
            Thread reader = new Thread(this::readServerLimit, "springtune-server-limit-" + poolName());
            reader.setDaemon(true);
            reader.start();
        }
    }

    /**
     * Reads the server's connection limit once (only when a budget is configured), with one connection borrowed from
     * the pool like any other request. It runs on its own short-lived thread so the sampler never waits behind
     * threads queuing for a saturated pool; it opens no connection outside the pool. The limit cannot change without
     * a server restart; a failed read is retried after the next tick.
     */
    private void readServerLimit() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            serverLimit = ServerConnectionLimit.read(connection);
        } catch (java.sql.SQLException | RuntimeException ex) {
            serverLimit = ServerConnectionLimit.notRead("reading it failed: " + ex.getMessage());
        } finally {
            readingServerLimit.set(false);
        }
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
