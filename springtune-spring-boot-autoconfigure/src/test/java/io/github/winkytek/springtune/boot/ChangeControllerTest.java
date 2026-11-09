package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.boot.ApplyGuard.Caller;
import io.github.winkytek.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winkytek.springtune.boot.ChangeAuditEntry.Outcome;
import io.github.winkytek.springtune.boot.ChangeController.State;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The apply-mode state machine, driven tick by tick with a {@link MutableClock}, fake writers and synthetic
 * cumulative counters (1000 acquires and usages per 10 s tick).
 */
class ChangeControllerTest {

    private static final String RULE = "ST-REC-HIKARI-001";
    private static final String KNOB = "hikari.orders.maximumPoolSize";
    private static final Caller OPS = new Caller("ops", true);

    /** In-memory knob: maximumPoolSize with minimumIdle moving along when equal. Records every write. */
    static final class FakeWriter implements KnobWriter {
        final Map<String, Long> values = new LinkedHashMap<>();
        final List<Map<String, Long>> writes = new ArrayList<>();
        boolean failWrites;

        FakeWriter(long max) {
            values.put("maximumPoolSize", max);
            values.put("minimumIdle", max);
        }

        @Override
        public String setting() {
            return "maximumPoolSize";
        }

        @Override
        public Map<String, Long> read() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public Map<String, Long> plan(long target) {
            Map<String, Long> plan = new LinkedHashMap<>();
            plan.put("maximumPoolSize", target);
            if (values.get("minimumIdle").equals(values.get("maximumPoolSize"))) {
                plan.put("minimumIdle", target);
            }
            return plan;
        }

        @Override
        public void write(Map<String, Long> target) {
            if (failWrites) {
                throw new IllegalStateException("pool rejected the value");
            }
            writes.add(new LinkedHashMap<>(target));
            values.putAll(target);
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final SpringTuneProperties properties = new SpringTuneProperties();
    private final List<ChangeAuditEntry> events = new ArrayList<>();
    private final Map<String, Double> counters = new LinkedHashMap<>();
    private FakeWriter writer;
    private List<WritableKnob> knobs;
    private ChangeController controller;

    /** Per-tick traffic: mean hold, mean acquire, timeouts, acquires. */
    private double holdMs = 15;
    private double acquireMs = 40;
    private double timeoutsPerTick = 0;
    private double acquiresPerTick = 1000;
    private boolean recommend = true;
    /** When set, the recommendation is ST-REC-HIKARI-003 DECREASE to this value. */
    private Long decreaseTo;
    /** What the fake pool holds (capacityObtained); {@code null}: not reported. */
    private volatile WritableKnob.Capacity capacity;
    /** What the next server-limit re-read returns, how often it ran, and whether it ran under the lock. */
    private volatile ServerConnectionLimit serverLimit = ServerConnectionLimit.notApplicable();
    private final java.util.concurrent.atomic.AtomicInteger serverReads = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean serverReadUnderLock;
    private volatile java.util.concurrent.CountDownLatch serverReadGate;

    @BeforeEach
    void setUp() {
        properties.setMode(SpringTuneProperties.Mode.APPLY);
        writer = new FakeWriter(10);
        counters.putAll(Map.of(Verification.ACQUIRES, 0.0, Verification.ACQUIRE_MS, 0.0, Verification.USAGES, 0.0,
                Verification.USAGE_MS, 0.0, Verification.TIMEOUTS, 0.0));
        knobs = new ArrayList<>(List.of(new WritableKnob(KNOB, "hikari:orders", WritableKnob.Kind.HIKARI, 10, writer,
                () -> Map.copyOf(counters), this::readServerLimit, () -> capacity)));
        controller = newController();
    }

    private ChangeController newController() {
        return new ChangeController(properties, clock, () -> knobs, this::recommendations, Optional.of(13), events::add);
    }

    private ServerConnectionLimit readServerLimit() {
        serverReads.incrementAndGet();
        if (Thread.holdsLock(controller)) {
            serverReadUnderLock = true;
        }
        java.util.concurrent.CountDownLatch gate = serverReadGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return serverLimit;
    }

    private static ServerConnectionLimit known(int limit, Instant at) {
        return new ServerConnectionLimit(ServerConnectionLimit.Status.KNOWN, limit, "PostgreSQL",
                "PostgreSQL max_connections " + (limit + 3) + " - superuser_reserved_connections 3"
                        + " - reserved_connections 0 = " + limit).checkedAt(at);
    }

    private List<Recommendation> recommendations(Instant now) {
        if (!recommend) {
            return List.of();
        }
        long current = writer.values.get("maximumPoolSize");
        if (decreaseTo != null) {
            return List.of(new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, current, decreaseTo, "idle",
                    Map.of(), Confidence.MEDIUM, List.of(), now));
        }
        if (current >= 13) {
            return List.of(new Recommendation(RULE, KNOB, Action.HOLD, current, current, "at budget cap", Map.of(),
                    Confidence.MEDIUM, List.of(), now));
        }
        return List.of(new Recommendation(RULE, KNOB, Action.INCREASE, current, Math.min(current + 3, 13), "saturated",
                Map.of(), Confidence.HIGH, List.of(), now));
    }

    private void tick() {
        counters.merge(Verification.ACQUIRES, acquiresPerTick, Double::sum);
        counters.merge(Verification.ACQUIRE_MS, acquiresPerTick * acquireMs, Double::sum);
        counters.merge(Verification.USAGES, acquiresPerTick, Double::sum);
        counters.merge(Verification.USAGE_MS, acquiresPerTick * holdMs, Double::sum);
        counters.merge(Verification.TIMEOUTS, timeoutsPerTick, Double::sum);
        controller.tick(clock.instant());
        clock.advance(Duration.ofSeconds(10));
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    private ChangeResponse apply() {
        return controller.handle(new ChangeRequest("apply", RULE, KNOB, writer.values.get("maximumPoolSize"),
                "threads wait for connections"), OPS);
    }

    @Test
    void healthyChangeIsConfirmedThenCoolsDown() {
        ticks(31);
        ChangeResponse response = apply();
        assertThat(response.status()).isEqualTo(200);
        assertThat(writer.values).containsEntry("maximumPoolSize", 13L).containsEntry("minimumIdle", 13L);
        assertThat(controller.state(KNOB)).isEqualTo(State.VERIFYING);
        assertThat(response.change().outcome()).isEqualTo(Outcome.VERIFYING);
        assertThat(response.change().who()).isEqualTo("ops");
        assertThat(response.change().before()).containsEntry("maximumPoolSize", 10L).containsEntry("minimumIdle", 10L);

        acquireMs = 5; // the bigger pool removes the wait
        ticks(34); // 30 s warmup + 5 min verification
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(done.verdict()).isEqualTo("OK");
        assertThat(done.evidence()).containsKey("meanConnectionHoldTime").containsKey("meanAcquireTime");
        assertThat(done.finishedAt()).isNotNull();
        assertThat(controller.state(KNOB)).isEqualTo(State.CONFIRMED);
        assertThat(writer.values).containsEntry("maximumPoolSize", 13L);

        // Cooling down: a further change is refused even with a fresh recommendation.
        assertThat(apply().code()).isEqualTo("COOLDOWN");
        clock.advance(Duration.ofMinutes(15));
        assertThat(controller.state(KNOB)).isEqualTo(State.IDLE);

        assertThat(events).extracting(ChangeAuditEntry::outcome)
                .containsSubsequence(Outcome.VERIFYING, Outcome.CONFIRMED, Outcome.REFUSED);
    }

    @Test
    void slowerHoldsAreRevertedAndTheKnobIsLockedLonger() {
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        holdMs = 40; // more connections made each one slower (limit 1.5 x 15 + 5 = 27.5 ms)
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(done.verdict()).isEqualTo("REGRESSED");
        assertThat(done.reasons()).anyMatch(r -> r.startsWith("R2"));
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L).containsEntry("minimumIdle", 10L);
        // Shrink order on revert: written as one map, the writer orders the setters (tested in the writer tests).
        assertThat(writer.writes).hasSize(2);
        assertThat(controller.state(KNOB)).isEqualTo(State.REVERTED);

        holdMs = 15;
        clock.advance(Duration.ofMinutes(30));
        ticks(31);
        assertThat(apply().code()).isEqualTo("COOLDOWN");
        clock.advance(Duration.ofMinutes(30));
        ticks(31); // fresh baseline history
        assertThat(apply().status()).isEqualTo(200);
    }

    @Test
    void timeoutsFailFastBeforeTheWindowEnds() {
        ticks(31);
        apply();
        ticks(3); // warmup
        timeoutsPerTick = 5;
        ticks(2);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(done.reasons().get(0)).contains("Fast fail");
        assertThat(done.reasons()).anyMatch(r -> r.startsWith("R1"));
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
    }

    @Test
    void inconclusiveIsRevertedByDefault() {
        ticks(31);
        apply();
        acquiresPerTick = 1; // traffic vanished
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(done.verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(done.reasons().get(0)).contains("on-inconclusive=revert");
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
    }

    @Test
    void inconclusiveIsKeptOnlyWhenConfigured() {
        properties.getApply().setOnInconclusive(SpringTuneProperties.Apply.OnInconclusive.KEEP);
        controller = newController();
        ticks(31);
        apply();
        acquiresPerTick = 1;
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.KEPT_INCONCLUSIVE);
        assertThat(writer.values).containsEntry("maximumPoolSize", 13L);
    }

    @Test
    void someoneElseChangingTheValueSupersedesTheChange() {
        ticks(31);
        apply();
        ticks(5);
        writer.values.put("maximumPoolSize", 20L); // e.g. an operator via JMX
        ticks(1);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(writer.values).containsEntry("maximumPoolSize", 20L);
        assertThat(controller.state(KNOB)).isEqualTo(State.SUPERSEDED);
    }

    @Test
    void failedRevertFreezesApplyModeUntilRestart() {
        ticks(31);
        apply();
        holdMs = 40;
        writer.failWrites = true;
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.REVERT_FAILED);
        assertThat(controller.frozen()).isTrue();
        assertThat(controller.state(KNOB)).isEqualTo(State.REVERT_FAILED);
        writer.failWrites = false;
        clock.advance(Duration.ofHours(5));
        assertThat(apply().code()).isEqualTo("FROZEN");
    }

    @Test
    void freezeRevertsTheChangeInFlightAndRefusesFurtherApplies() {
        ticks(31);
        apply();
        ChangeResponse freeze = controller.handle(new ChangeRequest("freeze", null, null, null, "incident"), OPS);
        assertThat(freeze.status()).isEqualTo(200);
        assertThat(freeze.change().outcome()).isEqualTo(Outcome.FROZEN);
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
        assertThat(controller.audit().get(1).outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(controller.audit().get(1).reasons().get(0)).contains("Frozen by ops: incident");
        assertThat(apply().code()).isEqualTo("FROZEN");
        assertThat(controller.view().frozen()).isTrue();
    }

    @Test
    void onlyOneChangeInFlightPerProcess() {
        FakeWriter other = new FakeWriter(8);
        knobs.add(new WritableKnob("executor.app.corePoolSize", "executor:app", WritableKnob.Kind.EXECUTOR, 8, other,
                () -> Map.of(Verification.COMPLETED, 0.0, Verification.REJECTED, 0.0)));
        ticks(31);
        apply();
        ChangeResponse second = controller.handle(new ChangeRequest("apply", "ST-REC-EXEC-001",
                "executor.app.corePoolSize", 8L, "backlog"), OPS);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("CHANGE_IN_FLIGHT");
        assertThat(other.writes).isEmpty();
    }

    @Test
    void refusalsAreAuditedWithTheCaller() {
        ticks(31);
        ChangeResponse stale = controller.handle(new ChangeRequest("apply", RULE, KNOB, 9L, "typo"),
                new Caller("alice", true));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.change().who()).isEqualTo("alice");
        assertThat(stale.change().code()).isEqualTo("STALE_EXPECTED_CURRENT");
        ChangeResponse anonymous = controller.handle(new ChangeRequest("apply", RULE, KNOB, 10L, "x"),
                new Caller(null, false));
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.change().who()).isEqualTo("anonymous");
        assertThat(writer.writes).isEmpty();
        assertThat(events).hasSize(2).allMatch(e -> e.outcome() == Outcome.REFUSED);
        // Both are logged and published, but only the authenticated refusal is kept in the bounded audit, so
        // anonymous requests cannot push genuine entries out of it.
        assertThat(controller.audit()).singleElement().satisfies(e -> assertThat(e.who()).isEqualTo("alice"));
    }

    @Test
    void applyNeedsBaselineHistoryAndACurrentRecommendation() {
        ticks(5);
        assertThat(apply().code()).isEqualTo("BASELINE_NOT_COVERED");
        ticks(26);
        recommend = false;
        assertThat(apply().code()).isEqualTo("NO_CURRENT_RECOMMENDATION");
        recommend = true;
        assertThat(apply().status()).isEqualTo(200);
    }

    @Test
    void confirmedChangeCanBeRevertedOnRequest() {
        ticks(31);
        apply();
        ticks(34);
        assertThat(controller.audit().get(0).outcome()).isEqualTo(Outcome.CONFIRMED);
        ChangeResponse revert = controller.handle(new ChangeRequest("revert", null, KNOB, null, "back to config"), OPS);
        assertThat(revert.status()).isEqualTo(200);
        assertThat(revert.change().action()).isEqualTo("revert");
        assertThat(revert.change().outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L).containsEntry("minimumIdle", 10L);
        assertThat(controller.handle(new ChangeRequest("revert", null, KNOB, null, null), OPS).code())
                .isEqualTo("NOTHING_TO_REVERT");
    }

    @Test
    void viewShowsConfiguredVersusEffectiveValuesAndLimits() {
        ticks(31);
        apply();
        TuningSnapshot.Apply view = controller.view();
        assertThat(view.enabled()).isTrue();
        assertThat(view.knobs()).singleElement().satisfies(k -> {
            assertThat(k.configuredValue()).isEqualTo(10);
            assertThat(k.effectiveValue()).isEqualTo(13);
            assertThat(k.state()).isEqualTo("VERIFYING");
            assertThat(k.maxStep()).isEqualTo(7);
            assertThat(k.max()).isEqualTo(13);
        });
        assertThat(view.guardrails()).containsEntry("onInconclusive", "REVERT").containsKey("changesInFlight");
        assertThat(view.audit()).hasSize(1);
    }

    @Test
    void auditIsBounded() {
        properties.getApply().setAuditSize(3);
        controller = newController();
        for (int i = 0; i < 5; i++) {
            controller.handle(new ChangeRequest("apply", RULE, KNOB, 1L, "x"), OPS);
        }
        assertThat(controller.audit()).hasSize(3).extracting(ChangeAuditEntry::id)
                .containsExactly("change-5", "change-4", "change-3");
    }

    /** Refusals during verification push the in-flight entry out of a bounded audit; freeze must still work. */
    @Test
    void freezeStillFreezesWhenTheInFlightEntryWasEvictedFromTheAudit() {
        properties.getApply().setAuditSize(2);
        controller = newController();
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        for (int i = 0; i < 3; i++) {
            assertThat(apply().code()).isEqualTo("CHANGE_IN_FLIGHT"); // e.g. a retrying client
        }
        ChangeResponse freeze = controller.handle(new ChangeRequest("freeze", null, null, null, "incident"), OPS);
        assertThat(freeze.status()).isEqualTo(200);
        assertThat(controller.frozen()).isTrue();
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
        assertThat(controller.audit()).anyMatch(e -> e.action().equals("apply") && e.outcome() == Outcome.REVERTED);
        assertThat(apply().code()).isEqualTo("FROZEN");
    }

    @Test
    void confirmationIsRecordedWhenTheInFlightEntryWasEvictedFromTheAudit() {
        properties.getApply().setAuditSize(2);
        controller = newController();
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        for (int i = 0; i < 3; i++) {
            apply();
        }
        acquireMs = 5;
        ticks(34);
        assertThat(controller.state(KNOB)).isEqualTo(State.CONFIRMED);
        assertThat(controller.audit().get(0).outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(events).extracting(ChangeAuditEntry::outcome).contains(Outcome.CONFIRMED);
        assertThat(apply().code()).isEqualTo("COOLDOWN");
        ChangeResponse revert = controller.handle(new ChangeRequest("revert", null, KNOB, null, "back"), OPS);
        assertThat(revert.status()).isEqualTo(200);
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
    }

    @Test
    void concurrentAppliesWriteOnce() throws Exception {
        ticks(31);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<ChangeResponse>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return controller.handle(new ChangeRequest("apply", RULE, KNOB, 10L, "race"), OPS);
            }));
        }
        go.countDown();
        List<String> codes = new ArrayList<>();
        for (java.util.concurrent.Future<ChangeResponse> result : results) {
            codes.add(result.get().code());
        }
        pool.shutdown();
        assertThat(codes).containsOnlyOnce("OK");
        assertThat(writer.writes).hasSize(1);
        assertThat(writer.values).containsEntry("maximumPoolSize", 13L);
    }

    @Test
    void auditLogLinesCannotBeForgedWithLineBreaks() {
        ChangeResponse refused = controller.handle(new ChangeRequest("apply", RULE, KNOB, 1L,
                "x\n2026-01-01 INFO springtune.audit change-99 apply by admin outcome=CONFIRMED"), OPS);
        assertThat(ChangeController.auditLine(refused.change())).doesNotContain("\n").doesNotContain("\r")
                .contains("x\\n2026-01-01");
    }

    // ------------------------------------------------------------------ capacityObtained (R2)

    @Test
    void raiseThatReachesItsTargetRecordsCapacityObtained() {
        capacity = new WritableKnob.Capacity(10, true);
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        ticks(2);
        capacity = new WritableKnob.Capacity(13, false); // Hikari opened the three new connections
        acquireMs = 5;
        ticks(32);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(done.capacityObtained()).isTrue();
        assertThat(done.capacity()).isEqualTo("Capacity obtained: the pool reached 13 connections (target 13).");
        assertThat(done.reasons()).last().isEqualTo(done.capacity());
        assertThat(ChangeController.auditLine(done)).contains("capacityObtained=true");
        assertThat(MicrometerSupport.capacityTag(done)).isEqualTo("true");
    }

    /** The surge case: the server refused the new connections, nothing got worse. Recorded, not reverted. */
    @Test
    void confirmedRaiseThatNeverObtainedItsCapacitySaysSoAndIsNotReverted() {
        capacity = new WritableKnob.Capacity(10, true);
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        ticks(34); // threads keep waiting, the pool stays at 10, acquire and hold times unchanged
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(done.verdict()).isEqualTo("OK");
        assertThat(done.capacityObtained()).isFalse();
        assertThat(done.capacity()).startsWith("Capacity NOT obtained: the pool never exceeded 10 connections;"
                + " target 13, although threads waited for a connection (connection creation refused or failing?)")
                .contains("Recorded only");
        assertThat(done.reasons()).contains(done.capacity());
        assertThat(writer.values).containsEntry("maximumPoolSize", 13L);
        assertThat(writer.writes).hasSize(1); // no revert
        assertThat(controller.state(KNOB)).isEqualTo(State.CONFIRMED);
        assertThat(ChangeController.auditLine(done)).contains("capacityObtained=false");
        assertThat(MicrometerSupport.capacityTag(done)).isEqualTo("false");
    }

    @Test
    void capacityNotObtainedWithoutDemandSaysThereWasNoDemand() {
        capacity = new WritableKnob.Capacity(11, false);
        ticks(31);
        apply();
        acquireMs = 5;
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(done.capacityObtained()).isFalse();
        assertThat(done.capacity()).contains("never exceeded 11 connections; target 13; no thread waited for a"
                + " connection while it was smaller (no demand for more yet)");
    }

    @Test
    void capacityIsRecordedOnARevertedRaiseToo() {
        capacity = new WritableKnob.Capacity(13, true);
        ticks(31);
        apply();
        holdMs = 40;
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome()).isEqualTo(Outcome.REVERTED);
        assertThat(done.verdict()).isEqualTo("REGRESSED");
        assertThat(done.capacityObtained()).isTrue();
    }

    @Test
    void capacityObtainedIsNotApplicableToADecreaseOrWhenNotReported() {
        capacity = new WritableKnob.Capacity(10, false);
        decreaseTo = 8L;
        ticks(31);
        ChangeResponse lowered = controller.handle(new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 10L, "idle"),
                OPS);
        assertThat(lowered.status()).as(lowered.message()).isEqualTo(200);
        ticks(34);
        ChangeAuditEntry done = controller.audit().get(0);
        assertThat(done.outcome().isFinal()).isTrue();
        assertThat(done.capacityObtained()).isNull();
        assertThat(done.capacity()).startsWith("capacityObtained not applicable: maximumPoolSize was lowered (10 -> 8)");
        assertThat(done.reasons()).noneMatch(r -> r.contains("capacityObtained"));
        assertThat(ChangeController.auditLine(done)).doesNotContain("capacityObtained=");
        assertThat(MicrometerSupport.capacityTag(done)).isEqualTo("not_applicable");

        // A component that does not report its capacity: not measured, also null.
        capacity = null;
        decreaseTo = null;
        setUp();
        ticks(31);
        apply();
        acquireMs = 5;
        ticks(34);
        assertThat(controller.audit().get(0).capacityObtained()).isNull();
        assertThat(controller.audit().get(0).capacity()).startsWith("capacityObtained not measured");
    }

    @Test
    void changesCounterIsTaggedWithCapacityObtained() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        MicrometerSupport.ChangeMetrics metrics = new MicrometerSupport.ChangeMetrics();
        metrics.bindTo(registry);
        capacity = new WritableKnob.Capacity(10, true);
        ticks(31);
        apply();
        ticks(34);
        assertThat(apply().code()).isEqualTo("COOLDOWN");
        events.forEach(e -> metrics.onApplicationEvent(new SpringTuneChangeEvent(this, e)));
        assertThat(registry.get("springtune.changes").tag("outcome", "confirmed").tag("capacity_obtained", "false")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("springtune.changes").tag("outcome", "refused")
                .tag("capacity_obtained", "not_applicable").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("springtune.changes").counters()).hasSize(2);
    }

    // ------------------------------------------------------------------ server limit re-read per apply (R3)

    @Test
    void serverLimitIsReReadForEveryApplyAndALoweredLimitRefusesTheNextRaise() {
        properties.getBudget().setDatabaseMaxConnections(20);
        controller = newController();
        serverLimit = known(20, clock.instant());
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        assertThat(serverReads).hasValue(1);
        controller.handle(new ChangeRequest("revert", null, KNOB, null, "test"), OPS);
        clock.advance(Duration.ofMinutes(61));
        ticks(31);

        // An operator lowered max_connections (and restarted the server) since the last read.
        serverLimit = known(10, clock.instant());
        ChangeResponse lowered = apply();
        assertThat(serverReads).hasValue(2);
        assertThat(lowered.status()).isEqualTo(409);
        assertThat(lowered.code()).isEqualTo("BUDGET_EXCEEDS_SERVER");
        assertThat(lowered.message()).contains("= 10").contains("at most 10");
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);

        // The re-read fails: refused, the last known value and its time are named.
        serverLimit = ServerConnectionLimit.notRead("no answer within connectionTimeout (250 ms) at "
                + clock.instant() + "; last known limit 10, read at 2026-01-01T01:11:20Z");
        ChangeResponse unknown = apply();
        assertThat(unknown.status()).isEqualTo(409);
        assertThat(unknown.code()).isEqualTo("SERVER_LIMIT_UNKNOWN");
        assertThat(unknown.message()).contains("last known limit 10, read at");
        assertThat(writer.writes).hasSize(2); // the first apply and its revert only

        serverLimit = known(20, clock.instant());
        assertThat(apply().status()).isEqualTo(200);
        assertThat(serverReads).hasValue(4);
        assertThat(serverReadUnderLock).as("re-read while holding the controller lock").isFalse();
    }

    @Test
    void refusedRequestsDoNotReadTheServerLimit() {
        serverLimit = known(20, clock.instant());
        ticks(31);
        controller.handle(new ChangeRequest("apply", RULE, KNOB, 10L, "x"), new Caller(null, false));
        controller.handle(new ChangeRequest("apply", RULE, null, 10L, "x"), OPS); // invalid request
        assertThat(serverReads).hasValue(0);
        assertThat(apply().status()).isEqualTo(200);
        assertThat(apply().code()).isEqualTo("CHANGE_IN_FLIGHT");
        controller.handle(new ChangeRequest("freeze", null, null, null, "x"), OPS);
        assertThat(apply().code()).isEqualTo("FROZEN");
        assertThat(serverReads).hasValue(1);
    }

    @Test
    void aSlowServerLimitReadNeverBlocksTheSamplerTick() throws Exception {
        serverLimit = known(20, clock.instant());
        ticks(31);
        serverReadGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService http = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ChangeResponse> pending = http.submit(this::apply);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (serverReads.get() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(serverReads).hasValue(1);
            java.util.concurrent.ExecutorService sampler = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                sampler.submit(() -> controller.tick(clock.instant())).get(5, java.util.concurrent.TimeUnit.SECONDS);
                sampler.submit(() -> controller.view()).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                sampler.shutdownNow();
            }
            assertThat(pending).isNotDone();
            serverReadGate.countDown();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            serverReadGate.countDown();
            http.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ races around the unlocked re-read (review 5)

    /** Starts {@code n} applies on their own threads and waits until all of them block in the server-limit read. */
    private List<java.util.concurrent.Future<ChangeResponse>> appliesBlockedInTheRead(
            java.util.concurrent.ExecutorService http, int n) throws InterruptedException {
        serverReadGate = new java.util.concurrent.CountDownLatch(1);
        long expected = writer.values.get("maximumPoolSize");
        List<java.util.concurrent.Future<ChangeResponse>> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pending.add(http.submit(() -> controller.handle(new ChangeRequest("apply", RULE, KNOB, expected,
                    "threads wait for connections"), OPS)));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (serverReads.get() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(serverReads).as("applies blocked in the server-limit read").hasValue(n);
        return pending;
    }

    @Test
    void twoAppliesThatBothPassedThePreChecksAndReadTheLimitStillMakeOneChange() throws Exception {
        properties.getBudget().setDatabaseMaxConnections(20);
        controller = newController();
        serverLimit = known(20, clock.instant());
        ticks(31);
        java.util.concurrent.ExecutorService http = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<ChangeResponse>> pending = appliesBlockedInTheRead(http, 2);
            serverReadGate.countDown();
            List<String> codes = new ArrayList<>();
            for (java.util.concurrent.Future<ChangeResponse> f : pending) {
                codes.add(f.get(5, java.util.concurrent.TimeUnit.SECONDS).code());
            }
            assertThat(codes).containsExactlyInAnyOrder("OK", "CHANGE_IN_FLIGHT");
            assertThat(writer.writes).hasSize(1);
            assertThat(controller.state(KNOB)).isEqualTo(State.VERIFYING);
        } finally {
            serverReadGate.countDown();
            http.shutdownNow();
        }
    }

    @Test
    void freezeArrivingDuringTheReadWinsOverTheApply() throws Exception {
        properties.getBudget().setDatabaseMaxConnections(20);
        controller = newController();
        serverLimit = known(20, clock.instant());
        ticks(31);
        java.util.concurrent.ExecutorService http = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ChangeResponse> pending = appliesBlockedInTheRead(http, 1).get(0);
            assertThat(controller.handle(new ChangeRequest("freeze", null, null, null, "incident"), OPS).status())
                    .isEqualTo(200);
            serverReadGate.countDown();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).code()).isEqualTo("FROZEN");
            assertThat(writer.writes).isEmpty();
        } finally {
            serverReadGate.countDown();
            http.shutdownNow();
        }
    }

    @Test
    void stateChangedDuringTheReadIsJudgedAfterTheRead() throws Exception {
        properties.getBudget().setDatabaseMaxConnections(20);
        controller = newController();
        serverLimit = known(20, clock.instant());
        ticks(31);
        java.util.concurrent.ExecutorService http = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // apply mode switched off while the read runs
            java.util.concurrent.Future<ChangeResponse> disabled = appliesBlockedInTheRead(http, 1).get(0);
            properties.getApply().setEnabled(false);
            serverReadGate.countDown();
            assertThat(disabled.get(5, java.util.concurrent.TimeUnit.SECONDS).code()).isNotEqualTo("OK");
            properties.getApply().setEnabled(true);

            // someone else changed the live value while the read runs: the CAS sees the new value
            serverReads.set(0);
            java.util.concurrent.Future<ChangeResponse> stale = appliesBlockedInTheRead(http, 1).get(0);
            synchronized (controller) {
                writer.values.put("maximumPoolSize", 11L);
            }
            serverReadGate.countDown();
            assertThat(stale.get(5, java.util.concurrent.TimeUnit.SECONDS).code()).isEqualTo("STALE_EXPECTED_CURRENT");

            // the limit read is the one used: a limit lowered at the read refuses the raise
            synchronized (controller) {
                writer.values.put("maximumPoolSize", 10L);
            }
            serverReads.set(0);
            serverLimit = known(10, clock.instant());
            java.util.concurrent.Future<ChangeResponse> lowered = appliesBlockedInTheRead(http, 1).get(0);
            serverReadGate.countDown();
            assertThat(lowered.get(5, java.util.concurrent.TimeUnit.SECONDS).code()).isEqualTo("BUDGET_EXCEEDS_SERVER");
            assertThat(writer.writes).isEmpty();
        } finally {
            serverReadGate.countDown();
            http.shutdownNow();
        }
    }

    /** A clock that runs one action right after the next reading (to land between two readings of one request). */
    private static final class HookClock extends java.time.Clock {
        private final java.time.Clock delegate;
        private volatile Runnable afterNextReading;

        HookClock(java.time.Clock delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.time.ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant now = delegate.instant();
            Runnable hook = afterNextReading;
            afterNextReading = null;
            if (hook != null) {
                hook.run();
            }
            return now;
        }
    }

    /**
     * TOCTOU: the pre-check that decides whether the limit is worth reading ran while the knob was still cooling
     * down (so nothing was read), and the decision a moment later found the cooldown over. The raise must still be
     * checked against a freshly read limit, not approved with no check at all.
     */
    @Test
    void cooldownEndingBetweenThePreCheckAndTheDecisionStillChecksAFreshLimit() {
        properties.getBudget().setDatabaseMaxConnections(20);
        properties.getApply().setCooldownAfterRevert(Duration.ofSeconds(400));
        HookClock hook = new HookClock(clock);
        controller = new ChangeController(properties, hook, () -> knobs, this::recommendations, Optional.of(13),
                events::add);
        serverLimit = known(20, clock.instant());
        ticks(31);
        assertThat(apply().status()).isEqualTo(200);
        controller.handle(new ChangeRequest("revert", null, KNOB, null, "test"), OPS);
        ticks(31);
        clock.advance(Duration.ofSeconds(90).minusMillis(1)); // 1 ms before the cooldown ends
        assertThat(apply().code()).isEqualTo("COOLDOWN");
        int readsBefore = serverReads.get();

        serverLimit = known(10, clock.instant()); // max_connections lowered meanwhile
        hook.afterNextReading = () -> clock.advance(Duration.ofMillis(1));
        ChangeResponse response = apply();
        // Decided either before the cooldown ended (no read needed) or after it with a fresh read; never approved
        // on a read that was skipped because the knob was still cooling down at an earlier check.
        assertThat(response.code()).as(response.message()).isIn("COOLDOWN", "BUDGET_EXCEEDS_SERVER");
        assertThat(serverReads.get()).isEqualTo(response.code().equals("COOLDOWN") ? readsBefore : readsBefore + 1);
        assertThat(apply().code()).isEqualTo("BUDGET_EXCEEDS_SERVER");
        assertThat(writer.values).containsEntry("maximumPoolSize", 10L);
    }

    @Test
    void invalidGuardrailSettingsAreRejected() {
        properties.getApply().setMaxStep(0);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(this::newController)
                .withMessageContaining("max-step");
    }
}
