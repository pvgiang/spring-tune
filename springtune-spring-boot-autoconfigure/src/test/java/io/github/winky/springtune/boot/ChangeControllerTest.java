package io.github.winky.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.boot.ApplyGuard.Caller;
import io.github.winky.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winky.springtune.boot.ChangeAuditEntry.Outcome;
import io.github.winky.springtune.boot.ChangeController.State;
import io.github.winky.springtune.model.Recommendation;
import io.github.winky.springtune.model.Recommendation.Action;
import io.github.winky.springtune.model.Recommendation.Confidence;
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

    @BeforeEach
    void setUp() {
        properties.setMode(SpringTuneProperties.Mode.APPLY);
        writer = new FakeWriter(10);
        counters.putAll(Map.of(Verification.ACQUIRES, 0.0, Verification.ACQUIRE_MS, 0.0, Verification.USAGES, 0.0,
                Verification.USAGE_MS, 0.0, Verification.TIMEOUTS, 0.0));
        knobs = new ArrayList<>(List.of(new WritableKnob(KNOB, "hikari:orders", WritableKnob.Kind.HIKARI, 10, writer,
                () -> Map.copyOf(counters))));
        controller = newController();
    }

    private ChangeController newController() {
        return new ChangeController(properties, clock, () -> knobs, this::recommendations, Optional.of(13), events::add);
    }

    private List<Recommendation> recommendations(Instant now) {
        if (!recommend) {
            return List.of();
        }
        long current = writer.values.get("maximumPoolSize");
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

    @Test
    void invalidGuardrailSettingsAreRejected() {
        properties.getApply().setMaxStep(0);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(this::newController)
                .withMessageContaining("max-step");
    }
}
