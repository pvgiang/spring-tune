package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/**
 * AC-11 against a real HikariCP pool on H2 with real Micrometer timers: 8 threads compete for 2
 * connections. Only the clock is simulated: each sample is stamped 10 s after the previous one so a 5-minute
 * window is covered in about 2 s of real time.
 */
class HikariContentionIntegrationTest {

    private static final int THREADS = 8;
    private static final int SAMPLES = 32;

    private Recommendation runScenario(long holdMillis, String... properties) throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:contention" + holdMillis + ";DB_CLOSE_DELAY=-1");
        dataSource.setPoolName("orders");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(30_000);
        dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));

        AtomicBoolean running = new AtomicBoolean(true);
        List<Thread> workers = new ArrayList<>();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", HikariDataSource.class, () -> dataSource);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.refresh();

            SpringTuneProperties tuneProperties = new SpringTuneProperties();
            org.springframework.boot.context.properties.bind.Binder binder =
                    new org.springframework.boot.context.properties.bind.Binder(
                            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                                    toMap(properties)));
            binder.bind("springtune", org.springframework.boot.context.properties.bind.Bindable.ofInstance(tuneProperties));
            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            TuningService service = new TuningService(tuneProperties, context.getBeanFactory(), clock);

            // Start the pool explicitly (HikariCP starts lazily) so sampling never races pool initialization.
            dataSource.getConnection().close();
            for (int i = 0; i < THREADS; i++) {
                Thread worker = new Thread(() -> {
                    while (running.get()) {
                        try (Connection connection = dataSource.getConnection()) {
                            connection.createStatement().execute("SELECT 1");
                            Thread.sleep(holdMillis);
                        } catch (InterruptedException ex) {
                            return;
                        } catch (Exception ex) {
                            if (running.get() && Boolean.getBoolean("springtune.debug")) {
                                ex.printStackTrace();
                            }
                        }
                    }
                });
                worker.setDaemon(true);
                worker.start();
                workers.add(worker);
            }
            // Wait until contention is established instead of sleeping a fixed time.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() < THREADS / 2) {
                assertThat(System.nanoTime()).as("contention established within 10 s").isLessThan(deadline);
                Thread.sleep(10);
            }
            Thread.sleep(holdMillis * 2);
            for (int i = 0; i < SAMPLES; i++) {
                if (Boolean.getBoolean("springtune.debug")) {
                    System.out.println("pending=" + dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
                            + " active=" + dataSource.getHikariPoolMXBean().getActiveConnections());
                }
                service.sample();
                clock.advance(Duration.ofSeconds(10));
                Thread.sleep(60);
            }
            assertThat(service.recommendations()).hasSize(1);
            return service.recommendations().get(0);
        } finally {
            running.set(false);
            workers.forEach(Thread::interrupt);
            dataSource.close();
        }
    }

    private static java.util.Map<String, String> toMap(String... properties) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("springtune.sampling.interval", "10s");
        for (String property : properties) {
            String[] parts = property.split("=", 2);
            map.put(parts[0], parts[1]);
        }
        return map;
    }

    @Test
    void shortHoldsUnderContentionRecommendRaisingWithinBudget() throws Exception {
        Recommendation rec = runScenario(15, "springtune.budget.database-max-connections=100",
                "springtune.budget.max-replicas=6");
        assertThat(rec.ruleId()).isEqualTo("ST-REC-HIKARI-001");
        assertThat(rec.knobId()).isEqualTo("hikari.orders.maximumPoolSize");
        assertThat(rec.action()).isEqualTo(Action.INCREASE);
        assertThat(rec.currentValue()).isEqualTo(2);
        assertThat(rec.recommendedValue()).isEqualTo(3);
        assertThat(rec.evidence()).containsEntry("budgetCap", "13");
    }

    @Test
    void shortHoldsAtBudgetCapAreHeld() throws Exception {
        Recommendation rec = runScenario(15, "springtune.budget.database-max-connections=10",
                "springtune.budget.max-replicas=4");
        assertThat(rec.ruleId()).isEqualTo("ST-REC-HIKARI-001");
        assertThat(rec.action()).isEqualTo(Action.HOLD);
        assertThat(rec.reason()).contains("connection budget cap of 2");
    }

    @Test
    void longHoldsUnderContentionRecommendNotRaising() throws Exception {
        Recommendation rec = runScenario(700);
        assertThat(rec.ruleId()).isEqualTo("ST-REC-HIKARI-002");
        assertThat(rec.action()).isEqualTo(Action.HOLD);
        assertThat(rec.reason()).startsWith("Do not raise the pool");
        long holdMs = Long.parseLong(rec.evidence().get("meanConnectionHoldTime").replace(" ms", ""));
        assertThat(holdMs).isBetween(650L, 1500L);
    }
}
