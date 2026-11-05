package io.github.winkytek.springtune.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.github.winkytek.springtune.boot.SpringTuneProperties;
import io.github.winkytek.springtune.boot.TuningService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * One sampler tick ({@link TuningService#sample()}) in advise mode, the default: sample every component and
 * recompute recommendations. This is what the starter's scheduler runs every {@code springtune.sampling.interval}
 * (10 s by default).
 *
 * <p>Components are real: H2 in-memory {@link HikariDataSource}s with Micrometer metrics (as Boot wires them) and
 * initialized {@link ThreadPoolTaskExecutor}s, registered in a bean factory with a {@link SimpleMeterRegistry}.
 * {@code components=1} is one Hikari pool; {@code components=4} is two pools and two executors.
 *
 * <p>Steady state, not cold start: every tick advances a fake clock by the sampling interval, and setup first runs
 * enough ticks to fill the longest retention window (Hikari's 24 h low-utilization window = 8,641 samples per pool
 * at 10 s), so each measured tick adds one sample, evicts one and scans full windows.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class SamplerTickBenchmark {

    @Param({"1", "4"})
    public int components;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    private TuningService service;
    private SteppingClock clock;

    @Setup(Level.Trial)
    public void setUp() throws SQLException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        MeterRegistry registry = new SimpleMeterRegistry();
        beanFactory.registerSingleton("meterRegistry", registry);
        int pools = components == 1 ? 1 : components / 2;
        int executorCount = components - pools;
        for (int i = 0; i < pools; i++) {
            HikariDataSource dataSource = dataSource("pool" + i, registry);
            dataSources.add(dataSource);
            beanFactory.registerSingleton("dataSource" + i, dataSource);
        }
        for (int i = 0; i < executorCount; i++) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(4);
            executor.setMaxPoolSize(8);
            executor.setQueueCapacity(100);
            executor.setThreadNamePrefix("bench-exec" + i + "-");
            executor.initialize();
            executors.add(executor);
            beanFactory.registerSingleton("taskExecutor" + i, executor);
        }

        SpringTuneProperties properties = new SpringTuneProperties();
        properties.setMode(SpringTuneProperties.Mode.ADVISE);
        clock = new SteppingClock(Instant.parse("2026-01-01T00:00:00Z"), properties.getSampling().getInterval());
        service = new TuningService(properties, beanFactory, clock);
        if (service.components().size() != components) {
            throw new IllegalStateException("expected " + components + " components, found "
                    + service.components());
        }
        Duration longest = properties.getHikari().getLowUtilizationWindow()
                .plus(properties.getSampling().getInterval());
        long fill = longest.dividedBy(properties.getSampling().getInterval()) + 10;
        for (long i = 0; i < fill; i++) {
            service.sample();
        }
    }

    private static HikariDataSource dataSource(String name, MeterRegistry registry) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setMaximumPoolSize(10);
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        HikariDataSource dataSource = new HikariDataSource(config);
        // Borrow and return a few connections so the acquire/usage timers have counts, as in a running service.
        for (int i = 0; i < 5; i++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.isValid(1);
            }
        }
        return dataSource;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        dataSources.forEach(HikariDataSource::close);
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Benchmark
    public void tick(Blackhole blackhole) {
        clock.step();
        service.sample();
        blackhole.consume(service.recommendations());
    }

    /** A clock that only moves when told to, by one sampling interval. */
    static final class SteppingClock extends Clock {

        private final Duration step;
        private volatile Instant now;

        SteppingClock(Instant start, Duration step) {
            this.now = start;
            this.step = step;
        }

        void step() {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
