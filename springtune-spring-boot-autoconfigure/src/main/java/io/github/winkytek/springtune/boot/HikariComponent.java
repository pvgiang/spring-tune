package io.github.winkytek.springtune.boot;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Reads a {@link HikariDataSource}. Never calls a setter: in apply mode the only write path is the
 * {@link HikariPoolWriter} of its {@link #writableKnob() writable knob}.
 */
final class HikariComponent implements TunedComponent {

    /** Longest an apply waits for the server-limit re-read, whatever {@code connectionTimeout} says (0 = forever). */
    static final long MAX_SERVER_LIMIT_WAIT_MS = 30_000;

    private static final Log LOG = LogFactory.getLog(HikariComponent.class);

    /** Reads the server's limit with a borrowed connection; {@link ServerConnectionLimit#read} except in tests. */
    @FunctionalInterface
    interface LimitReader {
        ServerConnectionLimit read(Connection connection) throws SQLException;
    }

    private final String beanName;
    private final HikariDataSource dataSource;
    private final TimerReader timers;
    private final SpringTuneProperties properties;
    private final Optional<Integer> budgetCap;
    private final SampleWindow<HikariSample> samples;
    private volatile WritableKnob writableKnob;
    private volatile ServerConnectionLimit serverLimit;
    private final AtomicReference<CompletableFuture<ServerConnectionLimit>> reading = new AtomicReference<>();
    private final LimitReader limitReader;
    private final Clock clock;

    HikariComponent(String beanName, HikariDataSource dataSource, TimerReader timers, SpringTuneProperties properties,
            Optional<Integer> budgetCap) {
        this(beanName, dataSource, timers, properties, budgetCap, Clock.systemUTC(), ServerConnectionLimit::read);
    }

    HikariComponent(String beanName, HikariDataSource dataSource, TimerReader timers, SpringTuneProperties properties,
            Optional<Integer> budgetCap, Clock clock, LimitReader limitReader) {
        this.clock = clock;
        this.limitReader = limitReader;
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

    /**
     * The server's connection limit as last read (see {@link #sample(Instant)} and {@link #recheckServerLimit()}),
     * with when it was read and whether the latest re-read failed. Shown in the apply plan and the signals.
     */
    ServerConnectionLimit serverLimit() {
        return serverLimit;
    }

    /**
     * Re-reads the server's connection limit for an apply attempt and returns the <b>fresh</b> result: KNOWN,
     * UNSUPPORTED, NOT_APPLICABLE, or NOT_READ when the read failed or did not answer within the pool's
     * {@code connectionTimeout} (at most {@value #MAX_SERVER_LIMIT_WAIT_MS} ms). A failed re-read never falls back to
     * the cached value: the guard refuses the raise ({@code SERVER_LIMIT_UNKNOWN}); the cached value stays for the
     * apply plan with its "last checked at" time. Blocks the calling (request) thread only; the read itself runs on a
     * short-lived thread with one connection borrowed from the pool, and a read already running is joined.
     */
    ServerConnectionLimit recheckServerLimit() {
        if (budgetCap.isEmpty()) {
            return ServerConnectionLimit.notApplicable();
        }
        ServerConnectionLimit cached = serverLimit;
        if (cached.status() == ServerConnectionLimit.Status.UNSUPPORTED) {
            return cached; // the product does not change while the pool runs
        }
        long waitMs = serverLimitWaitMs();
        try {
            return startRead().get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return failedNow("no answer within connectionTimeout (" + waitMs + " ms) at " + clock.instant());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failedNow("interrupted while re-reading it");
        } catch (ExecutionException ex) {
            return failedNow("re-reading it failed: " + ex.getCause());
        }
    }

    /**
     * The bound of a server-limit read: how long an apply waits for it, and the network timeout of the connection
     * the read borrows ({@link #boundNetworkTimeout}). The pool's {@code connectionTimeout}, at most
     * {@value #MAX_SERVER_LIMIT_WAIT_MS} ms ({@code 0}, "wait forever", counts as that maximum).
     */
    long serverLimitWaitMs() {
        long configured = dataSource.getHikariConfigMXBean().getConnectionTimeout();
        return configured <= 0 ? MAX_SERVER_LIMIT_WAIT_MS : Math.min(configured, MAX_SERVER_LIMIT_WAIT_MS);
    }

    /**
     * Bounds every socket read of the limit query on the borrowed connection to {@code timeoutMs}
     * ({@link Connection#setNetworkTimeout}), so a read on a half-open TCP connection (pgjdbc's default
     * {@code socketTimeout=0} waits for the kernel, ~15 min) ends with an I/O error instead of pinning the connection
     * and the reader thread. HikariCP records the change (dirty bit) and restores the pool's own network timeout when
     * the connection is returned, so nothing leaks into the pool; a read that timed out fails with a connection
     * SQLState (08xxx), and HikariCP evicts that connection instead of returning it. A driver that cannot set it
     * ({@link SQLFeatureNotSupportedException}, or an {@link AbstractMethodError} from a pre-JDBC-4.1 driver) keeps
     * the previous behaviour: the apply still waits at most {@code timeoutMs}, but the read itself is unbounded.
     *
     * @return whether the bound was set
     */
    static boolean boundNetworkTimeout(Connection connection, long timeoutMs) throws SQLException {
        try {
            connection.setNetworkTimeout(Runnable::run, (int) Math.min(timeoutMs, Integer.MAX_VALUE));
            return true;
        } catch (SQLFeatureNotSupportedException | AbstractMethodError ex) {
            LOG.debug("SpringTune cannot bound the server-limit read with a network timeout: " + ex);
            return false;
        }
    }

    /** A NOT_READ result naming the last known value, without touching the cache (the read may still finish). */
    private ServerConnectionLimit failedNow(String why) {
        String last = serverLimit.lastKnown();
        return ServerConnectionLimit.notRead(why + (last == null ? "; never read successfully" : "; " + last));
    }

    /** The read in progress, or a new one on its own daemon thread. */
    private CompletableFuture<ServerConnectionLimit> startRead() {
        while (true) {
            CompletableFuture<ServerConnectionLimit> current = reading.get();
            if (current != null && !current.isDone()) {
                return current;
            }
            CompletableFuture<ServerConnectionLimit> next = new CompletableFuture<>();
            if (reading.compareAndSet(current, next)) {
                Thread reader = new Thread(() -> {
                    try {
                        next.complete(readServerLimit());
                    } catch (Throwable ex) {
                        // Never leave a read that cannot complete: every later apply would join it and time out.
                        next.completeExceptionally(ex);
                        throw ex;
                    }
                }, "springtune-server-limit-" + poolName());
                reader.setDaemon(true);
                reader.start();
                return next;
            }
        }
    }

    /** Total connections the pool holds now, and whether threads wait for one; {@code null} before it starts. */
    WritableKnob.Capacity capacity() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool == null ? null
                : new WritableKnob.Capacity(pool.getTotalConnections(), pool.getThreadsAwaitingConnection() > 0);
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
                    new HikariPoolWriter(dataSource::getHikariConfigMXBean), this::counters, this::recheckServerLimit,
                    this::capacity);
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
            signals.put("serverConnectionLimit", server.known() ? server.limit() + " (" + server.detail()
                    + (server.checkedAt() != null ? "; last checked at " + server.checkedAt() : "")
                    + (server.recheckFailure() != null ? "; " + server.recheckFailure() : "") + ")"
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
                timers.counter("hikaricp.connections.timeout", poolName()).orElse(0.0), pool.getTotalConnections()));
        if (serverLimit.status() == ServerConnectionLimit.Status.NOT_READ) {
            startRead(); // never waited for here
        }
    }

    /**
     * Reads the server's connection limit (only when a budget is configured), with one connection borrowed from the
     * pool like any other request, on the thread {@link #startRead()} created, so the sampler never waits behind
     * threads queuing for a saturated pool; it opens no connection outside the pool. Until a first read succeeds it
     * is retried after every tick; after that it is re-read before every apply ({@link #recheckServerLimit()}).
     * Returns the fresh result and updates the cached one; a failed re-read keeps the last known value, marked.
     * The query is bounded by {@link #serverLimitWaitMs()} ({@link #boundNetworkTimeout}), so a hung read releases
     * its connection within that bound.
     */
    private ServerConnectionLimit readServerLimit() {
        try (Connection connection = dataSource.getConnection()) {
            boundNetworkTimeout(connection, serverLimitWaitMs());
            ServerConnectionLimit read = limitReader.read(connection).checkedAt(clock.instant());
            serverLimit = read;
            return read;
        } catch (SQLException | RuntimeException ex) {
            String failure = "re-reading it failed at " + clock.instant() + ": " + ex.getMessage();
            ServerConnectionLimit cached = serverLimit;
            if (cached.known()) {
                serverLimit = cached.withRecheckFailure(failure);
                return ServerConnectionLimit.notRead(failure + "; " + cached.lastKnown());
            }
            serverLimit = ServerConnectionLimit.notRead("reading it failed at " + clock.instant() + ": "
                    + ex.getMessage() + "; never read successfully");
            return serverLimit;
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
