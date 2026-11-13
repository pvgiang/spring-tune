package io.github.winkytek.springtune.boot;

import java.util.List;

/**
 * Where one recommendation rule stands for one component, computed in the same pass (and by the same checks) that
 * decides the recommendation, so an empty {@code recommendations[]} can be told apart: still collecting, a required
 * meter missing, or evaluated with nothing to recommend. Listed as {@code evaluations[]} in {@code GET
 * /actuator/tuning} and, pseudonymized, in {@code GET /actuator/tuning/report}.
 *
 * @param component     component id, e.g. {@code hikari:orders-pool}
 * @param ruleId        rule, e.g. {@code ST-REC-HIKARI-001}
 * @param status        see {@link Status}
 * @param seenSamples   {@link Status#COLLECTING}: samples retained within the rule's window so far
 * @param minSamples    {@link Status#COLLECTING}: samples the window must contain at least
 * @param seenSeconds   {@link Status#COLLECTING}: seconds from the oldest retained sample to now (at most the window)
 * @param neededSeconds {@link Status#COLLECTING}: seconds the samples must reach back (window minus one sampling
 *                      interval)
 * @param missingMeters {@link Status#NO_SIGNAL}: Micrometer meter names the rule needs and does not get
 * @param supersededBy  {@link Status#QUIET}: the rule whose recommendation took precedence for this component, or
 *                      {@code null} when nothing fired
 */
public record RuleEvaluation(
        String component,
        String ruleId,
        Status status,
        Integer seenSamples,
        Integer minSamples,
        Long seenSeconds,
        Long neededSeconds,
        List<String> missingMeters,
        String supersededBy) {

    public RuleEvaluation {
        missingMeters = missingMeters == null ? null : List.copyOf(missingMeters);
    }

    public enum Status {
        /** The retained samples do not cover the rule's window yet: no decision is made on partial evidence. */
        COLLECTING,
        /** A meter the rule needs is missing, so the rule cannot fire; {@code missingMeters} names it. */
        NO_SIGNAL,
        /** Evaluated over a covered window: nothing to recommend (healthy, or another rule took precedence). */
        QUIET,
        /** Evaluated: this rule produced the component's current recommendation. */
        RECOMMENDING
    }

    static RuleEvaluation collecting(String component, String ruleId, SampleWindow.Coverage<?> coverage) {
        return new RuleEvaluation(component, ruleId, Status.COLLECTING, coverage.seenSamples(),
                SampleWindow.Coverage.MIN_SAMPLES, coverage.seen().toSeconds(), coverage.needed().toSeconds(), null,
                null);
    }

    static RuleEvaluation noSignal(String component, String ruleId, List<String> missingMeters) {
        return new RuleEvaluation(component, ruleId, Status.NO_SIGNAL, null, null, null, null, missingMeters, null);
    }

    static RuleEvaluation quiet(String component, String ruleId, String supersededBy) {
        return new RuleEvaluation(component, ruleId, Status.QUIET, null, null, null, null, null, supersededBy);
    }

    static RuleEvaluation recommending(String component, String ruleId) {
        return new RuleEvaluation(component, ruleId, Status.RECOMMENDING, null, null, null, null, null, null);
    }
}
