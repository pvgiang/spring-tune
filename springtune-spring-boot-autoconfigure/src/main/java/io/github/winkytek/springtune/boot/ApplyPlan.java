package io.github.winkytek.springtune.boot;

import java.util.List;

/**
 * How to apply one INCREASE/DECREASE recommendation, shown in advise mode as well (read-only): what to commit
 * to the configuration, the budget arithmetic behind the value and the side effects of the change.
 *
 * @param ruleId       recommendation rule
 * @param knobId       knob concerned
 * @param target       the bean (and pool) the change applies to
 * @param from         current value
 * @param to           recommended value
 * @param commit       property lines to commit, or instructions when the property prefix cannot be known
 * @param budget       connection budget arithmetic (Hikari), empty otherwise
 * @param sideEffects  settings that move along and how the component takes the change
 * @param persistence  a change applied at runtime (apply mode) is ephemeral; only the committed
 *                     configuration survives a restart
 */
public record ApplyPlan(
        String ruleId,
        String knobId,
        String target,
        long from,
        long to,
        List<String> commit,
        List<String> budget,
        List<String> sideEffects,
        String persistence) {

    public ApplyPlan {
        commit = List.copyOf(commit);
        budget = List.copyOf(budget);
        sideEffects = List.copyOf(sideEffects);
    }

    static final String EPHEMERAL = "ephemeral: a change applied at runtime (springtune.mode=apply) is lost on"
            + " restart; commit the lines above to keep it.";
}
