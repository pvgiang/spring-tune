package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether a change request may be executed. Pure functions: everything the decision depends on is
 * passed in, so every refusal is unit-tested ({@code ApplyGuardTest}).
 *
 * <p>A request can only apply the {@code recommendedValue} of a <b>current</b> INCREASE/DECREASE
 * recommendation (matched by {@code ruleId} + {@code knobId}, re-evaluated at request time), and only when
 * the caller's {@code expectedCurrent} equals the live value (compare-and-set). On top of that the value must
 * stay within the step and growth limits, raising a Hikari pool needs a configured connection budget that the
 * database server can honour ({@link ServerConnectionLimit}, re-read for every apply attempt; a raise is refused
 * when that read fails, and not checked only for databases SpringTune cannot read) and stays under its
 * per-instance cap, the knob must not be cooling down, and no other change may be in flight.
 */
final class ApplyGuard {

    private ApplyGuard() {
    }

    enum Code {
        INVALID_REQUEST(400),
        NOT_AUTHENTICATED(401),
        ROLE_REQUIRED(403),
        MODE_NOT_APPLY(400),
        DISABLED(409),
        FROZEN(409),
        CHANGE_IN_FLIGHT(409),
        UNKNOWN_KNOB(400),
        COOLDOWN(409),
        NO_CURRENT_RECOMMENDATION(409),
        RECOMMENDATION_IS_HOLD(409),
        STALE_EXPECTED_CURRENT(409),
        STALE_EXPECTED_TARGET(409),
        NO_BUDGET(400),
        BUDGET_EXCEEDS_SERVER(409),
        SERVER_LIMIT_UNKNOWN(409),
        ABOVE_BUDGET_CAP(400),
        STEP_LIMIT(400),
        GROWTH_LIMIT(400),
        BASELINE_NOT_COVERED(409),
        NOTHING_TO_REVERT(409);

        final int status;

        Code(int status) {
            this.status = status;
        }
    }

    record Refusal(Code code, String message) {
        int status() {
            return code.status;
        }
    }

    /** What {@code POST /actuator/tuning} carries. All fields are optional at the HTTP level and checked here. */
    /**
     * @param expectedTarget optional: the value the operator reviewed. When set, apply is refused (409) if the
     *                       fresh recommendation now proposes a different value.
     */
    record ChangeRequest(String action, String ruleId, String knobId, Long expectedCurrent, String reason,
            Long expectedTarget) {

        ChangeRequest(String action, String ruleId, String knobId, Long expectedCurrent, String reason) {
            this(action, ruleId, knobId, expectedCurrent, reason, null);
        }

        String normalizedAction() {
            return action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** @param name principal name, {@code null} when unauthenticated */
    record Caller(String name, boolean hasRequiredRole) {
    }

    record State(SpringTuneProperties.Mode mode, boolean enabled, boolean frozen, boolean changeInFlight) {
    }

    /**
     * @param cooldownUntil   end of the knob's cooldown, or {@code null}
     * @param baselineCovered whether counters cover the baseline window before now
     */
    record KnobFacts(String knobId, WritableKnob.Kind kind, long current, long configured, Instant cooldownUntil,
            boolean baselineCovered, ServerConnectionLimit serverLimit) {

        KnobFacts(String knobId, WritableKnob.Kind kind, long current, long configured, Instant cooldownUntil,
                boolean baselineCovered) {
            this(knobId, kind, current, configured, cooldownUntil, baselineCovered,
                    ServerConnectionLimit.notApplicable());
        }

        KnobFacts withServerLimit(ServerConnectionLimit limit) {
            return new KnobFacts(knobId, kind, current, configured, cooldownUntil, baselineCovered, limit);
        }
    }

    /** Effective bounds for the next change of a knob (shown by the endpoint). */
    record Limits(long maxStep, long growthMin, long growthMax, long min, long max) {
    }

    record Decision(Refusal refusal, Recommendation recommendation) {
        boolean approved() {
            return refusal == null;
        }

        static Decision refuse(Code code, String message) {
            return new Decision(new Refusal(code, message), null);
        }
    }

    static Optional<Refusal> checkCaller(Caller caller, String requiredRole) {
        if (caller.name() == null) {
            return Optional.of(new Refusal(Code.NOT_AUTHENTICATED,
                    "Apply mode accepts only authenticated callers. Secure /actuator/tuning with Spring Security."));
        }
        if (requiredRole != null && !requiredRole.isBlank() && !caller.hasRequiredRole()) {
            return Optional.of(new Refusal(Code.ROLE_REQUIRED,
                    "Caller '" + caller.name() + "' lacks the role " + requiredRole + " (springtune.apply.required-role)."));
        }
        return Optional.empty();
    }

    static Optional<Refusal> checkRequest(ChangeRequest request) {
        String action = request.normalizedAction();
        switch (action) {
            case "apply":
                if (blank(request.ruleId()) || blank(request.knobId()) || request.expectedCurrent() == null
                        || blank(request.reason())) {
                    return Optional.of(new Refusal(Code.INVALID_REQUEST,
                            "apply needs ruleId, knobId, expectedCurrent and reason."));
                }
                return Optional.empty();
            case "revert":
                if (blank(request.knobId())) {
                    return Optional.of(new Refusal(Code.INVALID_REQUEST, "revert needs knobId."));
                }
                return Optional.empty();
            case "freeze":
                return Optional.empty();
            default:
                return Optional.of(new Refusal(Code.INVALID_REQUEST,
                        "action must be apply, revert or freeze (was '" + request.action() + "')."));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static Limits limits(long current, long configured, WritableKnob.Kind kind, Optional<Integer> budgetCap,
            SpringTuneProperties.Apply settings) {
        long step = maxStep(current, settings.getMaxStep());
        long growthMin = Math.max(1, (long) Math.ceil(configured / settings.getMaxGrowthFactor()));
        long growthMax = Math.max(growthMin, (long) Math.floor(configured * settings.getMaxGrowthFactor()));
        long min = Math.max(growthMin, current - step);
        long max = Math.min(growthMax, current + step);
        if (kind == WritableKnob.Kind.HIKARI) {
            // Raising a pool needs a budget; without one the effective maximum is the current value.
            max = Math.min(max, budgetCap.map(Integer::longValue).orElse(current));
        }
        return new Limits(step, growthMin, growthMax, min, max);
    }

    static long maxStep(long current, double maxStep) {
        return Math.max(1, (long) Math.ceil(current * maxStep));
    }

    /** The recommendation a request applies: same {@code ruleId} and {@code knobId}. */
    static Optional<Recommendation> match(ChangeRequest request, List<Recommendation> recommendations) {
        return recommendations.stream()
                .filter(r -> r.ruleId().equals(request.ruleId()) && r.knobId().equals(request.knobId()))
                .findFirst();
    }

    /**
     * Whether {@link #checkApply} would judge this request as raising a Hikari pool (the branch that needs the
     * server's limit), given the same facts and recommendations: a matching non-HOLD recommendation whose target is
     * above the live value. The apply decides with exactly these inputs, and a request judged not to raise carries
     * {@link ServerConnectionLimit.Status#SKIPPED_FOR_DECREASE}, which {@link #checkApply} refuses for any raise.
     */
    static boolean raisesHikariPool(ChangeRequest request, KnobFacts knob, List<Recommendation> recommendations) {
        return knob.kind() == WritableKnob.Kind.HIKARI && match(request, recommendations)
                .filter(r -> r.action() != Action.HOLD && r.recommendedValue() > knob.current()).isPresent();
    }

    static Decision checkApply(ChangeRequest request, State state, Optional<KnobFacts> knobFacts,
            List<Recommendation> recommendations, Optional<Integer> budgetCap, SpringTuneProperties.Apply settings,
            Instant now) {
        return checkApply(request, state, knobFacts, recommendations, budgetCap, null, settings, now);
    }

    /**
     * @param databaseMaxConnections the configured {@code springtune.budget.database-max-connections}, compared
     *                               with the server's limit ({@link KnobFacts#serverLimit()}) before raising a pool
     */
    static Decision checkApply(ChangeRequest request, State state, Optional<KnobFacts> knobFacts,
            List<Recommendation> recommendations, Optional<Integer> budgetCap, Integer databaseMaxConnections,
            SpringTuneProperties.Apply settings, Instant now) {
        if (state.mode() != SpringTuneProperties.Mode.APPLY) {
            return Decision.refuse(Code.MODE_NOT_APPLY, "springtune.mode is " + state.mode() + ", not apply.");
        }
        if (!state.enabled()) {
            return Decision.refuse(Code.DISABLED, "springtune.apply.enabled=false: applying is switched off.");
        }
        if (state.frozen()) {
            return Decision.refuse(Code.FROZEN, "Apply mode is frozen until restart.");
        }
        if (state.changeInFlight()) {
            return Decision.refuse(Code.CHANGE_IN_FLIGHT,
                    "Another change is being verified; only one change is in flight per process.");
        }
        if (knobFacts.isEmpty()) {
            return Decision.refuse(Code.UNKNOWN_KNOB, "Knob " + request.knobId() + " is not writable in apply mode.");
        }
        KnobFacts knob = knobFacts.get();
        if (knob.cooldownUntil() != null && now.isBefore(knob.cooldownUntil())) {
            return Decision.refuse(Code.COOLDOWN, "Knob " + knob.knobId() + " is cooling down until "
                    + knob.cooldownUntil() + ".");
        }
        Optional<Recommendation> match = match(request, recommendations);
        if (match.isEmpty()) {
            return Decision.refuse(Code.NO_CURRENT_RECOMMENDATION, "There is no current " + request.ruleId()
                    + " recommendation for " + request.knobId() + " (re-evaluated now); nothing to apply.");
        }
        Recommendation rec = match.get();
        if (rec.action() == Action.HOLD) {
            return Decision.refuse(Code.RECOMMENDATION_IS_HOLD,
                    "The current recommendation is HOLD: " + rec.reason());
        }
        long current = knob.current();
        if (request.expectedCurrent() != current) {
            return Decision.refuse(Code.STALE_EXPECTED_CURRENT,
                    "expectedCurrent " + request.expectedCurrent() + " does not match the live value " + current + ".");
        }
        if (rec.currentValue() != current) {
            return Decision.refuse(Code.STALE_EXPECTED_CURRENT, "The recommendation was made for "
                    + rec.currentValue() + " but the live value is " + current + "; wait for the next evaluation.");
        }
        long target = rec.recommendedValue();
        if (request.expectedTarget() != null && request.expectedTarget() != target) {
            return Decision.refuse(Code.STALE_EXPECTED_TARGET, "expectedTarget " + request.expectedTarget()
                    + " does not match the current recommendation (" + target + "); review it again.");
        }
        if (knob.kind() == WritableKnob.Kind.HIKARI && target > current) {
            if (budgetCap.isEmpty()) {
                return Decision.refuse(Code.NO_BUDGET, "Raising maximumPoolSize needs a connection budget:"
                        + " set springtune.budget.database-max-connections and max-replicas.");
            }
            ServerConnectionLimit server = knob.serverLimit();
            if (server != null && server.status() == ServerConnectionLimit.Status.NOT_READ) {
                return Decision.refuse(Code.SERVER_LIMIT_UNKNOWN, "The database server's connection limit could not"
                        + " be re-read for this apply (" + server.detail() + "), so database-max-connections="
                        + databaseMaxConnections + " cannot be checked against it. Raising the pool is refused until"
                        + " it can be read; retry when a connection is available.");
            }
            if (server != null && server.status() == ServerConnectionLimit.Status.SKIPPED_FOR_DECREASE) {
                // Fail closed: the read was skipped because this apply was judged not to raise the pool. If it raises
                // now, that judgement is wrong or stale, and a raise is never approved without a fresh limit.
                return Decision.refuse(Code.SERVER_LIMIT_UNKNOWN, "The database server's connection limit was not"
                        + " read for this apply (" + server.detail() + "), but the recommendation raises the pool ("
                        + current + " -> " + target + "). Raising is refused without a fresh read; retry.");
            }
            if (server != null && server.known() && databaseMaxConnections != null
                    && databaseMaxConnections > server.limit()) {
                return Decision.refuse(Code.BUDGET_EXCEEDS_SERVER, "springtune.budget.database-max-connections="
                        + databaseMaxConnections + " is more than the database server accepts (" + server.detail()
                        + "), so the per-instance cap of " + budgetCap.get() + " is not safe. Set"
                        + " database-max-connections to at most " + server.limit() + " (minus what other services"
                        + " use) before raising the pool.");
            }
            if (target > budgetCap.get()) {
                return Decision.refuse(Code.ABOVE_BUDGET_CAP, target + " is above the per-instance connection budget"
                        + " cap of " + budgetCap.get() + ".");
            }
        }
        long step = maxStep(current, settings.getMaxStep());
        if (Math.abs(target - current) > step) {
            return Decision.refuse(Code.STEP_LIMIT, "Changing " + current + " to " + target + " exceeds the maximum"
                    + " step of " + step + " (springtune.apply.max-step=" + settings.getMaxStep() + "). Change the"
                    + " configuration instead, or apply in several reviewed steps.");
        }
        Limits limits = limits(current, knob.configured(), knob.kind(), budgetCap, settings);
        if (target < limits.growthMin() || target > limits.growthMax()) {
            return Decision.refuse(Code.GROWTH_LIMIT, target + " is outside [" + limits.growthMin() + ", "
                    + limits.growthMax() + "], the range allowed around the startup value " + knob.configured()
                    + " (springtune.apply.max-growth-factor=" + settings.getMaxGrowthFactor() + ").");
        }
        if (!knob.baselineCovered()) {
            return Decision.refuse(Code.BASELINE_NOT_COVERED, "Counters do not yet cover the baseline window of "
                    + Format.duration(settings.getBaseline()) + ", so the change could not be verified. Wait, or check"
                    + " that the component's metrics are available (Hikari needs its Micrometer timers).");
        }
        return new Decision(null, rec);
    }
}
