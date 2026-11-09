package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.boot.ApplyGuard.Caller;
import io.github.winkytek.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winkytek.springtune.boot.ApplyGuard.Code;
import io.github.winkytek.springtune.boot.ApplyGuard.Decision;
import io.github.winkytek.springtune.boot.ApplyGuard.KnobFacts;
import io.github.winkytek.springtune.boot.ApplyGuard.State;
import io.github.winkytek.springtune.boot.SpringTuneProperties.Mode;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every refusal of the apply guard, on synthetic inputs. */
class ApplyGuardTest {

    private static final Instant NOW = Instant.parse("2026-01-01T01:00:00Z");
    private static final String KNOB = "hikari.orders.maximumPoolSize";
    private static final String RULE = "ST-REC-HIKARI-001";

    private final SpringTuneProperties.Apply settings = new SpringTuneProperties().getApply();
    private final State ok = new State(Mode.APPLY, true, false, false);

    private static Recommendation rec(Action action, long current, long recommended) {
        return new Recommendation(RULE, KNOB, action, current, recommended, "test", Map.of(), Confidence.HIGH,
                List.of(), NOW);
    }

    private static KnobFacts knob(long current, long configured) {
        return new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, current, configured, null, true);
    }

    private static ChangeRequest apply(long expected) {
        return new ChangeRequest("apply", RULE, KNOB, expected, "pool saturated");
    }

    private Decision check(ChangeRequest request, State state, KnobFacts knob, List<Recommendation> recs,
            Optional<Integer> cap) {
        return ApplyGuard.checkApply(request, state, Optional.ofNullable(knob), recs, cap, settings, NOW);
    }

    private Code refusal(ChangeRequest request, State state, KnobFacts knob, List<Recommendation> recs,
            Optional<Integer> cap) {
        Decision decision = check(request, state, knob, recs, cap);
        assertThat(decision.approved()).as("refused").isFalse();
        return decision.refusal().code();
    }

    @Test
    void approvesCurrentIncreaseWithinAllLimits() {
        Decision decision = check(apply(10), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13));
        assertThat(decision.approved()).isTrue();
        assertThat(decision.recommendation().recommendedValue()).isEqualTo(13);
    }

    @Test
    void refusesOutsideApplyModeDisabledOrFrozen() {
        List<Recommendation> recs = List.of(rec(Action.INCREASE, 10, 13));
        assertThat(refusal(apply(10), new State(Mode.ADVISE, true, false, false), knob(10, 10), recs, Optional.of(13)))
                .isEqualTo(Code.MODE_NOT_APPLY);
        assertThat(refusal(apply(10), new State(Mode.APPLY, false, false, false), knob(10, 10), recs, Optional.of(13)))
                .isEqualTo(Code.DISABLED);
        assertThat(refusal(apply(10), new State(Mode.APPLY, true, true, false), knob(10, 10), recs, Optional.of(13)))
                .isEqualTo(Code.FROZEN);
    }

    @Test
    void refusesWhileAnotherChangeIsInFlight() {
        assertThat(refusal(apply(10), new State(Mode.APPLY, true, false, true), knob(10, 10),
                List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13))).isEqualTo(Code.CHANGE_IN_FLIGHT);
    }

    @Test
    void refusesUnknownKnob() {
        assertThat(refusal(apply(10), ok, null, List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13)))
                .isEqualTo(Code.UNKNOWN_KNOB);
    }

    @Test
    void refusesWithoutMatchingCurrentRecommendation() {
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(), Optional.of(13)))
                .isEqualTo(Code.NO_CURRENT_RECOMMENDATION);
        Recommendation otherRule = new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, 10, 8, "x", Map.of(),
                Confidence.HIGH, List.of(), NOW);
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(otherRule), Optional.of(13)))
                .isEqualTo(Code.NO_CURRENT_RECOMMENDATION);
    }

    @Test
    void refusesHold() {
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(rec(Action.HOLD, 10, 10)), Optional.of(13)))
                .isEqualTo(Code.RECOMMENDATION_IS_HOLD);
    }

    @Test
    void compareAndSetOnExpectedCurrentAndOnTheRecommendation() {
        Decision mismatch = check(apply(9), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13));
        assertThat(mismatch.refusal().code()).isEqualTo(Code.STALE_EXPECTED_CURRENT);
        assertThat(mismatch.refusal().status()).isEqualTo(409);
        // The live value moved since the recommendation was computed.
        assertThat(refusal(apply(11), ok, knob(11, 10), List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13)))
                .isEqualTo(Code.STALE_EXPECTED_CURRENT);
    }

    @Test
    void optionalExpectedTargetPinsTheReviewedValue() {
        List<Recommendation> recs = List.of(rec(Action.INCREASE, 10, 13));
        Decision moved = check(new ChangeRequest("apply", RULE, KNOB, 10L, "r",
                12L), ok, knob(10, 10), recs, Optional.of(13));
        assertThat(moved.refusal().code()).isEqualTo(Code.STALE_EXPECTED_TARGET);
        assertThat(moved.refusal().status()).isEqualTo(409);
        assertThat(check(new ChangeRequest("apply", RULE, KNOB, 10L, "r", 13L),
                ok, knob(10, 10), recs, Optional.of(13)).approved()).isTrue();
    }

    @Test
    void raisingAHikariPoolNeedsABudgetAndStaysUnderTheCap() {
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 13)), Optional.empty()))
                .isEqualTo(Code.NO_BUDGET);
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 13)), Optional.of(12)))
                .isEqualTo(Code.ABOVE_BUDGET_CAP);
        // Lowering needs no budget.
        Recommendation lower = new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, 10, 6, "x", Map.of(),
                Confidence.MEDIUM, List.of(), NOW);
        assertThat(check(new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 10L, "idle"), ok, knob(10, 10),
                List.of(lower), Optional.empty()).approved()).isTrue();
    }

    @Test
    void configuredBudgetAboveTheServersLimitRefusesARaise() {
        List<Recommendation> recs = List.of(rec(Action.INCREASE, 10, 13));
        ServerConnectionLimit server = new ServerConnectionLimit(ServerConnectionLimit.Status.KNOWN, 20, "PostgreSQL",
                "PostgreSQL max_connections 23 - superuser_reserved_connections 3 - reserved_connections 0 = 20");
        KnobFacts facts = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, null, true, server);
        Decision refused = ApplyGuard.checkApply(apply(10), ok, Optional.of(facts), recs, Optional.of(13), 100,
                settings, NOW);
        assertThat(refused.refusal().code()).isEqualTo(Code.BUDGET_EXCEEDS_SERVER);
        assertThat(refused.refusal().status()).isEqualTo(409);
        assertThat(refused.refusal().message()).contains("database-max-connections=100").contains("= 20")
                .contains("at most 20");
        // Equal to the server's limit is consistent.
        assertThat(ApplyGuard.checkApply(apply(10), ok, Optional.of(facts), recs, Optional.of(13), 20, settings, NOW)
                .approved()).isTrue();
        // A database SpringTune cannot read the limit of: not checked.
        KnobFacts unsupported = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, null, true,
                new ServerConnectionLimit(ServerConnectionLimit.Status.UNSUPPORTED, null, "H2", "cannot read"));
        assertThat(ApplyGuard.checkApply(apply(10), ok, Optional.of(unsupported), recs, Optional.of(13), 100,
                settings, NOW).approved()).isTrue();
        // The re-read for this apply failed: refused (fail closed), never approved on a stale value.
        KnobFacts unread = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, null, true,
                ServerConnectionLimit.notRead("no answer within connectionTimeout (250 ms); last known limit 20, read"
                        + " at 2026-01-01T00:00:00Z"));
        Decision unknown = ApplyGuard.checkApply(apply(10), ok, Optional.of(unread), recs, Optional.of(13), 20,
                settings, NOW);
        assertThat(unknown.refusal().code()).isEqualTo(Code.SERVER_LIMIT_UNKNOWN);
        assertThat(unknown.refusal().status()).isEqualTo(409);
        assertThat(unknown.refusal().message()).contains("last known limit 20, read at 2026-01-01T00:00:00Z")
                .contains("refused until it can be read");
        // Lowering a pool is never refused for it.
        Recommendation lower = new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, 10, 6, "x", Map.of(),
                Confidence.MEDIUM, List.of(), NOW);
        assertThat(ApplyGuard.checkApply(new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 10L, "idle"), ok,
                Optional.of(facts), List.of(lower), Optional.of(13), 100, settings, NOW).approved()).isTrue();
        assertThat(ApplyGuard.checkApply(new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 10L, "idle"), ok,
                Optional.of(unread), List.of(lower), Optional.of(13), 100, settings, NOW).approved()).isTrue();
    }

    /** Iteration 6 row 8: a read skipped because the apply was judged a decrease never lets a raise through. */
    @Test
    void readSkippedForADecreaseRefusesAnyRaise() {
        KnobFacts skipped = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, null, true,
                ServerConnectionLimit.skippedForDecrease());
        // Even with a budget that is consistent with any server, and a target within every limit.
        Decision raise = ApplyGuard.checkApply(apply(10), ok, Optional.of(skipped),
                List.of(rec(Action.INCREASE, 10, 11)), Optional.of(13), 13, settings, NOW);
        assertThat(raise.approved()).isFalse();
        assertThat(raise.refusal().code()).isEqualTo(Code.SERVER_LIMIT_UNKNOWN);
        assertThat(raise.refusal().status()).isEqualTo(409);
        assertThat(raise.refusal().message()).contains("not read for this apply").contains("10 -> 11");
        // The decrease it was skipped for is approved.
        Recommendation lower = new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, 10, 8, "x", Map.of(),
                Confidence.MEDIUM, List.of(), NOW);
        ChangeRequest lowerRequest = new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 10L, "idle");
        assertThat(ApplyGuard.checkApply(lowerRequest, ok, Optional.of(skipped), List.of(lower), Optional.of(13), 13,
                settings, NOW).approved()).isTrue();
        // And the direction the controller uses to decide whether to read is the guard's own.
        assertThat(ApplyGuard.raisesHikariPool(apply(10), skipped, List.of(rec(Action.INCREASE, 10, 11)))).isTrue();
        assertThat(ApplyGuard.raisesHikariPool(lowerRequest, skipped, List.of(lower))).isFalse();
        assertThat(ApplyGuard.raisesHikariPool(apply(10), skipped, List.of(rec(Action.HOLD, 10, 10)))).isFalse();
        assertThat(ApplyGuard.raisesHikariPool(apply(10), skipped, List.of(lower))).as("other rule").isFalse();
        assertThat(ApplyGuard.raisesHikariPool(apply(10), knob(9, 10), List.of(rec(Action.INCREASE, 9, 10))))
                .as("target above the live value").isTrue();
    }

    @Test
    void executorIncreaseNeedsNoBudget() {
        String knobId = "executor.app.corePoolSize";
        Recommendation rec = new Recommendation("ST-REC-EXEC-001", knobId, Action.INCREASE, 8, 10, "x", Map.of(),
                Confidence.MEDIUM, List.of(), NOW);
        KnobFacts facts = new KnobFacts(knobId, WritableKnob.Kind.EXECUTOR, 8, 8, null, true);
        assertThat(ApplyGuard.checkApply(new ChangeRequest("apply", "ST-REC-EXEC-001", knobId, 8L, "backlog"), ok,
                Optional.of(facts), List.of(rec), Optional.empty(), settings, NOW).approved()).isTrue();
    }

    @Test
    void stepLimitIsMaxOfOneAndCeilOfCurrentTimesMaxStep() {
        assertThat(ApplyGuard.maxStep(10, 0.5)).isEqualTo(5);
        assertThat(ApplyGuard.maxStep(13, 0.5)).isEqualTo(7);
        assertThat(ApplyGuard.maxStep(1, 0.1)).isEqualTo(1);
        assertThat(refusal(apply(10), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 16)), Optional.of(50)))
                .isEqualTo(Code.STEP_LIMIT);
        assertThat(check(apply(10), ok, knob(10, 10), List.of(rec(Action.INCREASE, 10, 15)), Optional.of(50))
                .approved()).isTrue();
        // Low-utilization decrease from 20 to 2 is a step of 18 > 10.
        Recommendation lower = new Recommendation("ST-REC-HIKARI-003", KNOB, Action.DECREASE, 20, 2, "x", Map.of(),
                Confidence.MEDIUM, List.of(), NOW);
        assertThat(refusal(new ChangeRequest("apply", "ST-REC-HIKARI-003", KNOB, 20L, "idle"), ok, knob(20, 20),
                List.of(lower), Optional.empty())).isEqualTo(Code.STEP_LIMIT);
    }

    @Test
    void growthStaysWithinFactorOfStartupValue() {
        // Startup 4: allowed range [2, 8]; current 7 -> 9 is a valid step but above 8.
        assertThat(refusal(apply(7), ok, knob(7, 4), List.of(rec(Action.INCREASE, 7, 9)), Optional.of(50)))
                .isEqualTo(Code.GROWTH_LIMIT);
        Recommendation lower = new Recommendation(RULE, KNOB, Action.DECREASE, 3, 1, "x", Map.of(), Confidence.LOW,
                List.of(), NOW);
        assertThat(refusal(apply(3), ok, knob(3, 4), List.of(lower), Optional.of(50))).isEqualTo(Code.GROWTH_LIMIT);
    }

    @Test
    void cooldownAndBaselineCoverage() {
        KnobFacts cooling = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, NOW.plusSeconds(60), true);
        assertThat(refusal(apply(10), ok, cooling, List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13)))
                .isEqualTo(Code.COOLDOWN);
        KnobFacts cooled = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, NOW, true);
        assertThat(check(apply(10), ok, cooled, List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13)).approved())
                .isTrue();
        KnobFacts young = new KnobFacts(KNOB, WritableKnob.Kind.HIKARI, 10, 10, null, false);
        assertThat(refusal(apply(10), ok, young, List.of(rec(Action.INCREASE, 10, 13)), Optional.of(13)))
                .isEqualTo(Code.BASELINE_NOT_COVERED);
    }

    @Test
    void requestValidationAndCaller() {
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("delete", null, null, null, null)))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo(Code.INVALID_REQUEST));
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("apply", RULE, KNOB, null, "x")))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(400));
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("apply", RULE, KNOB, 10L, " "))).isPresent();
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("APPLY", RULE, KNOB, 10L, "why"))).isEmpty();
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("revert", null, null, null, null))).isPresent();
        assertThat(ApplyGuard.checkRequest(new ChangeRequest("freeze", null, null, null, null))).isEmpty();

        assertThat(ApplyGuard.checkCaller(new Caller(null, true), null))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(401));
        assertThat(ApplyGuard.checkCaller(new Caller("viewer", false), "TUNER"))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(403));
        assertThat(ApplyGuard.checkCaller(new Caller("viewer", false), null)).isEmpty();
        assertThat(ApplyGuard.checkCaller(new Caller("ops", true), "TUNER")).isEmpty();
    }

    @Test
    void limitsShownByTheEndpoint() {
        ApplyGuard.Limits withBudget = ApplyGuard.limits(10, 10, WritableKnob.Kind.HIKARI, Optional.of(13), settings);
        assertThat(withBudget.min()).isEqualTo(5);
        assertThat(withBudget.max()).isEqualTo(13);
        assertThat(withBudget.maxStep()).isEqualTo(5);
        ApplyGuard.Limits noBudget = ApplyGuard.limits(10, 10, WritableKnob.Kind.HIKARI, Optional.empty(), settings);
        assertThat(noBudget.max()).isEqualTo(10);
        ApplyGuard.Limits executor = ApplyGuard.limits(8, 8, WritableKnob.Kind.EXECUTOR, Optional.empty(), settings);
        assertThat(executor.min()).isEqualTo(4);
        assertThat(executor.max()).isEqualTo(12);
    }
}
