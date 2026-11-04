package io.github.winky.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.github.winky.springtune.boot.ApplyGuard.Caller;
import io.github.winky.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winky.springtune.boot.ChangeAuditEntry.Outcome;
import io.github.winky.springtune.model.Recommendation;
import io.github.winky.springtune.model.Recommendation.Action;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Apply mode end to end against a real HikariCP pool on H2 with real Micrometer timers (no mocks): 8 threads
 * compete for 2 connections, the recommended increase is applied through the change controller, and the
 * sampler tick verifies it. Only the clock is simulated (10 s per tick, 100 ms of real time per tick).
 *
 * <p>The "database" is simulated in the worker: a connection is held for {@code fastMs} while at most
 * {@code saturatesAbove} connections are in use at once, and for {@code slowMs} beyond that (a database that
 * saturates above 2 concurrent sessions).
 */
class HikariApplyIntegrationTest {

    private static final int THREADS = 8;
    private static final String KNOB = "hikari.orders.maximumPoolSize";

    record Run(Recommendation recommendation, ChangeResponse applied, ChangeAuditEntry finalEntry,
            HikariDataSource dataSource, int maxTotalConnections, int maxTotalAfterWarmup, TuningSnapshot planSnapshot,
            SimpleMeterRegistry registry) {
    }

    private Run run(String db, long fastMs, long slowMs, int saturatesAbove) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        dataSource.setPoolName("orders");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(2);
        dataSource.setConnectionTimeout(30_000);
        dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger holders = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        List<ChangeAuditEntry> events = new CopyOnWriteArrayList<>();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("dataSource", HikariDataSource.class, () -> dataSource);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.refresh();

            SpringTuneProperties properties = new SpringTuneProperties();
            Map<String, String> map = new LinkedHashMap<>();
            map.put("springtune.mode", "apply");
            map.put("springtune.sampling.interval", "10s");
            map.put("springtune.budget.database-max-connections", "100");
            map.put("springtune.budget.max-replicas", "6");
            new Binder(new MapConfigurationPropertySource(map)).bind("springtune", Bindable.ofInstance(properties));
            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            TuningService service = new TuningService(properties, context.getBeanFactory(), clock);
            MicrometerSupport.ChangeMetrics metrics = new MicrometerSupport.ChangeMetrics();
            metrics.bindTo(registry);
            ChangeController controller = new ChangeController(properties, clock, service::writableKnobs,
                    service::evaluate, service.budgetCap(), entry -> {
                        events.add(entry);
                        metrics.onApplicationEvent(new SpringTuneChangeEvent(service, entry));
                    });
            service.attach(controller);

            dataSource.getConnection().close(); // start the pool so sampling never races its initialization
            for (int i = 0; i < THREADS; i++) {
                Thread worker = new Thread(() -> {
                    while (running.get()) {
                        try (Connection connection = dataSource.getConnection()) {
                            int inUse = holders.incrementAndGet();
                            try {
                                connection.createStatement().execute("SELECT 1");
                                Thread.sleep(inUse > saturatesAbove ? slowMs : fastMs);
                            } finally {
                                holders.decrementAndGet();
                            }
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
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() < THREADS / 2) {
                assertThat(System.nanoTime()).as("contention established within 10 s").isLessThan(deadline);
                Thread.sleep(10);
            }
            Thread.sleep(fastMs * 2);
            for (int i = 0; i < 32; i++) {
                tick(service, clock);
            }
            assertThat(service.recommendations()).hasSize(1);
            Recommendation rec = service.recommendations().get(0);
            TuningSnapshot planSnapshot = service.snapshot();

            ChangeResponse applied = controller.handle(new ChangeRequest("apply", rec.ruleId(), rec.knobId(),
                    rec.currentValue(), "threads wait for connections, holds are short"), new Caller("ops", true));

            int maxTotal = 0;
            int maxTotalAfterWarmup = 0;
            for (int i = 0; i < 40 && events.get(events.size() - 1).outcome() == Outcome.VERIFYING; i++) {
                tick(service, clock);
                int total = dataSource.getHikariPoolMXBean().getTotalConnections();
                maxTotal = Math.max(maxTotal, total);
                if (i >= 3) {
                    maxTotalAfterWarmup = Math.max(maxTotalAfterWarmup, total);
                }
            }
            // Hikari adds connections asynchronously; on a loaded machine the fill can trail the ticks. Give it up
            // to 5 s more real time (load still running) before judging whether the new capacity was used.
            long fillDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            int target = dataSource.getHikariConfigMXBean().getMaximumPoolSize();
            while (maxTotalAfterWarmup < target && System.nanoTime() < fillDeadline) {
                Thread.sleep(20);
                int total = dataSource.getHikariPoolMXBean().getTotalConnections();
                maxTotal = Math.max(maxTotal, total);
                maxTotalAfterWarmup = Math.max(maxTotalAfterWarmup, total);
            }
            return new Run(rec, applied, controller.audit().get(0), dataSource, maxTotal, maxTotalAfterWarmup,
                    planSnapshot, registry);
        } finally {
            running.set(false);
            workers.forEach(Thread::interrupt);
            for (Thread worker : workers) {
                worker.join(2000);
            }
        }
    }

    private static void tick(TuningService service, MutableClock clock) throws InterruptedException {
        service.sample();
        clock.advance(Duration.ofSeconds(10));
        Thread.sleep(100);
    }

    @Test
    void recommendedIncreaseIsAppliedVerifiedAndConfirmed() throws Exception {
        Run run = run("apply-ok", 15, 15, Integer.MAX_VALUE);
        try {
            assertThat(run.recommendation().ruleId()).isEqualTo("ST-REC-HIKARI-001");
            assertThat(run.recommendation().action()).isEqualTo(Action.INCREASE);
            assertThat(run.recommendation().recommendedValue()).isEqualTo(3);

            // The advise-mode plan for the same recommendation.
            ApplyPlan plan = run.planSnapshot().applyPlans().get(0);
            assertThat(plan.commit()).containsExactly("spring.datasource.hikari.maximum-pool-size=3",
                    "spring.datasource.hikari.minimum-idle=3");
            assertThat(plan.budget().get(0)).endsWith("= 13");
            assertThat(plan.persistence()).startsWith("ephemeral");
            assertThat(run.planSnapshot().apply().knobs()).singleElement()
                    .satisfies(k -> assertThat(k.configuredValue()).isEqualTo(2));

            assertThat(run.applied().status()).as(run.applied().message()).isEqualTo(200);
            assertThat(run.dataSource().getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(3);
            assertThat(run.dataSource().getHikariConfigMXBean().getMinimumIdle()).isEqualTo(3);
            assertThat(run.maxTotalConnections()).isLessThanOrEqualTo(3);
            assertThat(run.maxTotalAfterWarmup()).as("the pool used its new capacity; final %s %s",
                    run.finalEntry().outcome(), run.finalEntry().reasons()).isEqualTo(3);

            ChangeAuditEntry entry = run.finalEntry();
            assertThat(entry.outcome()).as(entry.reasons().toString()).isEqualTo(Outcome.CONFIRMED);
            assertThat(entry.verdict()).isEqualTo("OK");
            assertThat(entry.who()).isEqualTo("ops");
            assertThat(entry.why()).isEqualTo("threads wait for connections, holds are short");
            assertThat(entry.ruleId()).isEqualTo("ST-REC-HIKARI-001");
            assertThat(entry.knobId()).isEqualTo(KNOB);
            assertThat(entry.before()).containsEntry("maximumPoolSize", 2L).containsEntry("minimumIdle", 2L);
            assertThat(entry.after()).containsEntry("maximumPoolSize", 3L).containsEntry("minimumIdle", 3L);
            assertThat(entry.reasons()).anyMatch(r -> r.startsWith("R2")).anyMatch(r -> r.startsWith("R3"));
            assertThat(entry.evidence()).containsKeys("meanConnectionHoldTime", "meanAcquireTime",
                    "timeoutsPerAttempt", "acquireAttemptsPerSecond");
            assertThat(entry.finishedAt()).isNotNull();
            assertThat(run.registry().get("springtune.changes").tag("outcome", "confirmed").counter().count())
                    .isEqualTo(1.0);
        } finally {
            run.dataSource().close();
        }
    }

    @Test
    void increaseThatSlowsEveryConnectionDownIsReverted() throws Exception {
        Run run = run("apply-regress", 15, 35, 2);
        try {
            assertThat(run.recommendation().action()).isEqualTo(Action.INCREASE);
            assertThat(run.applied().status()).as(run.applied().message()).isEqualTo(200);
            ChangeAuditEntry entry = run.finalEntry();
            assertThat(entry.outcome()).as(entry.reasons() + " " + entry.evidence()).isEqualTo(Outcome.REVERTED);
            assertThat(entry.verdict()).isEqualTo("REGRESSED");
            assertThat(entry.reasons()).anyMatch(r -> r.startsWith("R2") && r.contains("slower than before"));
            // Back to the original pool; Hikari retires the extra connection lazily.
            assertThat(run.dataSource().getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(2);
            assertThat(run.dataSource().getHikariConfigMXBean().getMinimumIdle()).isEqualTo(2);
            assertThat(run.maxTotalConnections()).isLessThanOrEqualTo(3);
            assertThat(run.registry().get("springtune.changes").tag("outcome", "reverted").counter().count())
                    .isEqualTo(1.0);
        } finally {
            run.dataSource().close();
        }
    }
}
