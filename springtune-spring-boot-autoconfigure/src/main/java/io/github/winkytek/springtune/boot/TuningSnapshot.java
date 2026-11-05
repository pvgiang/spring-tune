package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload of {@code GET /actuator/tuning}.
 *
 * @param applySupported whether this process accepts {@code POST /actuator/tuning} ({@code mode=apply})
 * @param applyPlans     how to apply each INCREASE/DECREASE recommendation (advise and apply mode)
 * @param apply          apply-mode state (guardrails, writable knobs, audit), or {@code null} in other modes
 */
public record TuningSnapshot(
        String mode,
        boolean applySupported,
        Instant generatedAt,
        Budget budget,
        List<Component> components,
        List<Recommendation> recommendations,
        List<ApplyPlan> applyPlans,
        Apply apply) {

    public record Component(String id, int samples, List<Knob> knobs, Map<String, Object> signals) {
    }

    /**
     * @param perInstanceCap upper bound for maximumPoolSize recommendations, or {@code null} when not configured
     */
    public record Budget(Integer databaseMaxConnections, Integer maxReplicas, double safetyFactor,
            Integer perInstanceCap) {
    }

    /**
     * Apply-mode state.
     *
     * @param enabled      {@code springtune.apply.enabled}
     * @param frozen       whether applying is frozen until restart (freeze action or a failed revert)
     * @param frozenReason why, or {@code null}
     * @param requiredRole {@code springtune.apply.required-role}, or {@code null}
     * @param guardrails   effective guardrail settings
     * @param knobs        writable knobs with their configured (startup) and effective (live) values
     * @param audit        newest first, at most {@code springtune.apply.audit-size}
     */
    public record Apply(boolean enabled, boolean frozen, String frozenReason, String requiredRole,
            Map<String, String> guardrails, List<WritableKnobView> knobs, List<ChangeAuditEntry> audit) {
    }

    /**
     * @param configuredValue value at startup (what the application's configuration says)
     * @param effectiveValue  live value; differs from {@code configuredValue} after an applied change, which is
     *                        lost on restart
     * @param min             smallest value the next change may set (step and growth limits)
     * @param max             largest value the next change may set (step, growth and, for Hikari, budget cap)
     * @param maxStep         largest allowed difference from the current value
     * @param state           IDLE, VERIFYING, CONFIRMED, REVERTED, SUPERSEDED (the last three while cooling
     *                        down) or REVERT_FAILED
     * @param cooldownUntil   end of the knob's cooldown, or {@code null}
     */
    public record WritableKnobView(String knobId, String component, String setting, long configuredValue,
            long effectiveValue, long min, long max, long maxStep, String state, Instant stateSince,
            Instant cooldownUntil) {
    }
}
