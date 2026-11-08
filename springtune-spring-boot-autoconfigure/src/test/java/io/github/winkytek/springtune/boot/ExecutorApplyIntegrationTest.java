package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.boot.ApplyGuard.Caller;
import io.github.winkytek.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winkytek.springtune.boot.ChangeAuditEntry.Outcome;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Apply mode end to end against a real {@link ThreadPoolTaskExecutor} (Spring's default {@code AbortPolicy}) with
 * no mocks: an <b>open-loop</b> submitter offers tasks at a fixed arrival rate (seeded jitter, the seed is
 * printed), whatever the executor does, and counts the {@link TaskRejectedException}s it receives. The executor
 * serves 2 tasks at a time, so its backlog is sustained; ST-REC-EXEC-001 recommends a raise, it is applied through
 * the change controller and the sampler tick verifies it on the executor's own counters (rule R6). Only the clock
 * is simulated (10 s per tick, 100 ms of real time per tick), as in {@link HikariApplyIntegrationTest}.
 *
 * <p>The executor records every size setter call, so the write order is asserted on the live executor:
 * growing writes {@code maxPoolSize} before {@code corePoolSize}, shrinking the reverse ({@code ThreadPoolExecutor}
 * throws on the wrong order when the change crosses the other bound).
 *
 * <p>A task sleeps {@code fastMs} while at most {@code saturatesAbove} tasks run at once and {@code slowMs} beyond
 * that (a downstream that saturates above 2 concurrent callers). Assertions are on counts, enums and pool sizes,
 * never on elapsed time.
 */
class ExecutorApplyIntegrationTest {

    private static final double ARRIVALS_PER_SECOND = 130; // capacity 2 threads x 20 ms = 100/s; 3 threads = 150/s

    /** A real executor that records the size writes made after {@link #record()}. */
    static final class RecordingExecutor extends ThreadPoolTaskExecutor {

        final List<String> writes = new CopyOnWriteArrayList<>();
        private volatile boolean recording;

        void record() {
            recording = true;
        }

        @Override
        public void setCorePoolSize(int corePoolSize) {
            super.setCorePoolSize(corePoolSize);
            if (recording) {
                writes.add("core=" + corePoolSize);
            }
        }

        @Override
        public void setMaxPoolSize(int maxPoolSize) {
            super.setMaxPoolSize(maxPoolSize);
            if (recording) {
                writes.add("max=" + maxPoolSize);
            }
        }
    }

    record Run(Recommendation recommendation, ChangeResponse applied, ChangeAuditEntry finalEntry,
            List<String> writesDuringVerification, RecordingExecutor executor, ChangeController controller,
            TuningService service, MutableClock clock, long submitted, long rejectedSeenBySubmitter,
            long rejectedCounted, Object rejectionHandler, int poolSizeAfter, SimpleMeterRegistry registry) {
    }

    /** Runs the scenario; {@code afterVerification} runs while the load is still on (e.g. a manual revert). */
    private Run run(int queueCapacity, long fastMs, long slowMs, int saturatesAbove,
            java.util.function.Function<Run, Run> afterVerification) throws Exception {
        // Rerun a printed seed with -Dspringtune.test.seed=<seed> (the arrival times, not the scheduling, repeat).
        long seed = Long.getLong("springtune.test.seed", System.nanoTime());
        System.out.println("ExecutorApplyIntegrationTest submitter seed=" + seed);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingExecutor executor = new RecordingExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("worker-");

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger inUse = new AtomicInteger();
        AtomicLong submitted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        List<ChangeAuditEntry> events = new CopyOnWriteArrayList<>();
        Thread submitter = null;
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("worker", ThreadPoolTaskExecutor.class, () -> executor);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.refresh();

            SpringTuneProperties properties = new SpringTuneProperties();
            Map<String, String> map = new LinkedHashMap<>();
            map.put("springtune.mode", "apply");
            map.put("springtune.sampling.interval", "10s");
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

            // The first tick discovers the executor and installs the rejection counter (apply mode only), so
            // every rejection the submitter sees from now on must also be counted.
            tick(service, clock);
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(RejectionCounter.class);
            RejectionCounter counter = (RejectionCounter) pool.getRejectedExecutionHandler();

            Runnable task = () -> {
                int concurrent = inUse.incrementAndGet();
                try {
                    Thread.sleep(concurrent > saturatesAbove ? slowMs : fastMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    inUse.decrementAndGet();
                }
            };
            submitter = new Thread(() -> {
                // Open loop: the next arrival time does not depend on how the executor answered. Mean interval
                // 1/rate, uniform jitter of +-30% around it. The submitter never waits for a task.
                Random random = new Random(seed);
                long intervalNanos = (long) (1_000_000_000L / ARRIVALS_PER_SECOND);
                long next = System.nanoTime();
                while (running.get()) {
                    next += (long) (intervalNanos * (0.7 + 0.6 * random.nextDouble()));
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    if (!running.get()) {
                        return;
                    }
                    try {
                        submitted.incrementAndGet();
                        executor.execute(task);
                    } catch (TaskRejectedException ex) {
                        rejected.incrementAndGet();
                    }
                }
            }, "open-loop-submitter");
            submitter.setDaemon(true);
            submitter.start();

            awaitSteadyBacklog(pool);
            for (int i = 0; i < 32; i++) {
                tick(service, clock);
            }
            assertThat(service.recommendations()).as("recommendations; samples %s", service.snapshot().components())
                    .hasSize(1);
            Recommendation rec = service.recommendations().get(0);

            executor.record();
            ChangeResponse applied = controller.handle(new ChangeRequest("apply", rec.ruleId(), rec.knobId(),
                    rec.currentValue(), "tasks queue while every thread is busy"), new Caller("ops", true));
            for (int i = 0; i < 40 && events.get(events.size() - 1).outcome() == Outcome.VERIFYING; i++) {
                tick(service, clock);
            }
            Run result = new Run(rec, applied, controller.audit().get(0), List.copyOf(executor.writes), executor,
                    controller, service, clock, 0, 0, 0, null, 0, registry);
            result = afterVerification.apply(result);

            // The pool gives up threads above a lowered maximum as they poll for their next task (bounded wait).
            long shrinkDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (pool.getPoolSize() > pool.getMaximumPoolSize() && System.nanoTime() < shrinkDeadline) {
                Thread.sleep(10);
            }
            int poolSizeAfter = pool.getPoolSize();

            running.set(false);
            submitter.join(5000);
            return new Run(result.recommendation(), result.applied(), result.finalEntry(),
                    result.writesDuringVerification(), executor, controller, service, clock, submitted.get(),
                    rejected.get(), counter.rejected(), counter.delegate(), poolSizeAfter, registry);
        } finally {
            running.set(false);
            if (submitter != null) {
                submitter.join(5000);
            }
        }
    }

    /** Wait (bounded, 15 s) until the queue held tasks in at least 80% of 20 ms readings over one second. */
    private static void awaitSteadyBacklog(ThreadPoolExecutor pool) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            int backlogged = 0;
            int readings = 50;
            for (int i = 0; i < readings; i++) {
                if (pool.getQueue().size() > 0) {
                    backlogged++;
                }
                Thread.sleep(20);
            }
            if (backlogged >= readings * 0.8) {
                return;
            }
            assertThat(System.nanoTime()).as("steady backlog within 15 s (last second: %s of %s readings)",
                    backlogged, readings).isLessThan(deadline);
        }
    }

    private static void tick(TuningService service, MutableClock clock) throws InterruptedException {
        service.sample();
        clock.advance(Duration.ofSeconds(10));
        Thread.sleep(100);
    }

    private static void assertRejectionsCountedAndOriginalPolicyRan(Run run) {
        assertThat(run.rejectionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(run.rejectedSeenBySubmitter()).as("AbortPolicy rejections the submitter received").isPositive();
        assertThat(run.rejectedCounted()).as("counted by RejectionCounter, then passed on to AbortPolicy")
                .isEqualTo(run.rejectedSeenBySubmitter());
        assertThat(run.submitted()).isGreaterThan(run.rejectedSeenBySubmitter());
    }

    @Test
    void sustainedBacklogRaiseIsAppliedGrowingFirstAndConfirmed() throws Exception {
        Run run = run(10, 20, 20, Integer.MAX_VALUE, r -> r);
        Recommendation rec = run.recommendation();
        assertThat(rec.ruleId()).isEqualTo("ST-REC-EXEC-001");
        assertThat(rec.knobId()).isEqualTo("executor.worker.maxPoolSize");
        assertThat(rec.action()).isEqualTo(Action.INCREASE);
        assertThat(rec.currentValue()).isEqualTo(2);
        assertThat(rec.recommendedValue()).isEqualTo(3);

        assertThat(run.applied().status()).as(run.applied().message()).isEqualTo(200);
        // Bounded queue: only maxPoolSize moves (core 2 stays below it).
        assertThat(run.writesDuringVerification()).containsExactly("max=3");
        assertThat(run.executor().getMaxPoolSize()).isEqualTo(3);
        assertThat(run.executor().getCorePoolSize()).isEqualTo(2);

        ChangeAuditEntry entry = run.finalEntry();
        assertThat(entry.outcome()).as(entry.reasons() + " " + entry.evidence()).isEqualTo(Outcome.CONFIRMED);
        assertThat(entry.verdict()).isEqualTo("OK");
        assertThat(entry.who()).isEqualTo("ops");
        assertThat(entry.before()).containsExactly(Map.entry("maxPoolSize", 2L));
        assertThat(entry.after()).containsExactly(Map.entry("maxPoolSize", 3L));
        assertThat(entry.reasons()).anyMatch(r -> r.startsWith("R6") && r.contains("not higher than before"));
        assertThat(entry.evidence()).containsKeys("completedTasksPerSecond", "rejectedTasks");
        assertThat(run.registry().get("springtune.changes").tag("outcome", "confirmed").counter().count())
                .isEqualTo(1.0);
        assertRejectionsCountedAndOriginalPolicyRan(run);
        System.out.println("confirmed: " + entry.evidence() + " submitted=" + run.submitted() + " rejected="
                + run.rejectedSeenBySubmitter());
    }

    @Test
    void raiseThatSlowsEveryTaskDownIsRevertedOnR6() throws Exception {
        Run run = run(10, 20, 45, 2, r -> r);
        assertThat(run.recommendation().action()).isEqualTo(Action.INCREASE);
        assertThat(run.recommendation().knobId()).isEqualTo("executor.worker.maxPoolSize");
        assertThat(run.applied().status()).as(run.applied().message()).isEqualTo(200);

        ChangeAuditEntry entry = run.finalEntry();
        assertThat(entry.outcome()).as(entry.reasons() + " " + entry.evidence()).isEqualTo(Outcome.REVERTED);
        assertThat(entry.verdict()).as(entry.reasons().toString()).isEqualTo("REGRESSED");
        assertThat(entry.reasons()).anyMatch(r -> r.startsWith("R6") && r.contains("more tasks are rejected"));
        // Raised, then written back (shrinking: the core size first, which is not part of this change).
        assertThat(run.writesDuringVerification()).containsExactly("max=3", "max=2");
        assertThat(run.executor().getMaxPoolSize()).isEqualTo(2);
        assertThat(run.executor().getCorePoolSize()).isEqualTo(2);
        assertThat(run.poolSizeAfter()).as("threads above the restored maximum retire").isLessThanOrEqualTo(2);
        assertThat(run.registry().get("springtune.changes").tag("outcome", "reverted").counter().count())
                .isEqualTo(1.0);
        assertRejectionsCountedAndOriginalPolicyRan(run);
        System.out.println("reverted: " + entry.evidence() + " submitted=" + run.submitted() + " rejected="
                + run.rejectedSeenBySubmitter());
    }

    /**
     * Unbounded queue, core = max = 2: the knob is {@code corePoolSize}, and raising it to 3 crosses the maximum.
     * Applying writes the maximum first; a manual revert of the confirmed change writes the core size first.
     * The wrong order would throw {@code IllegalArgumentException} in {@code ThreadPoolExecutor}.
     */
    @Test
    void coreSizeAcrossTheMaximumIsWrittenInOrderBothWays() throws Exception {
        Run run = run(Integer.MAX_VALUE, 20, 20, Integer.MAX_VALUE, r -> {
            assertThat(r.finalEntry().outcome()).as(r.finalEntry().reasons().toString()).isEqualTo(Outcome.CONFIRMED);
            r.executor().writes.clear();
            ChangeResponse revert = r.controller().handle(new ChangeRequest("revert", null,
                    r.recommendation().knobId(), null, "rollback drill", null), new Caller("ops", true));
            assertThat(revert.status()).as(revert.message()).isEqualTo(200);
            assertThat(revert.change().outcome()).isEqualTo(Outcome.REVERTED);
            return new Run(r.recommendation(), r.applied(), r.finalEntry(), List.copyOf(r.writesDuringVerification()),
                    r.executor(), r.controller(), r.service(), r.clock(), 0, 0, 0, null, 0, r.registry());
        });
        assertThat(run.recommendation().knobId()).isEqualTo("executor.worker.corePoolSize");
        assertThat(run.recommendation().recommendedValue()).isEqualTo(3);
        assertThat(run.applied().status()).as(run.applied().message()).isEqualTo(200);
        assertThat(run.applied().change().after()).containsExactly(Map.entry("corePoolSize", 3L),
                Map.entry("maxPoolSize", 3L));
        // Growing: maximum first, then core.
        assertThat(run.writesDuringVerification()).containsExactly("max=3", "core=3");
        // Shrinking (manual revert): core first, then maximum.
        assertThat(run.executor().writes).containsExactly("core=2", "max=2");
        assertThat(run.executor().getCorePoolSize()).isEqualTo(2);
        assertThat(run.executor().getMaxPoolSize()).isEqualTo(2);
        assertThat(run.controller().audit()).extracting(ChangeAuditEntry::outcome)
                .startsWith(Outcome.REVERTED, Outcome.CONFIRMED);
        // An unbounded queue never rejects.
        assertThat(run.rejectedSeenBySubmitter()).isZero();
        assertThat(run.rejectedCounted()).isZero();
        assertThat(run.poolSizeAfter()).isLessThanOrEqualTo(2);
    }
}
