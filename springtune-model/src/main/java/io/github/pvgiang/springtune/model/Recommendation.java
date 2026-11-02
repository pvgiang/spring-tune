package io.github.pvgiang.springtune.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An explained suggestion produced from live signals. Never applied automatically in this release.
 *
 * @param ruleId           recommendation rule id, e.g. {@code ST-REC-HIKARI-001}
 * @param knobId           the knob concerned
 * @param action           what to do with the knob
 * @param currentValue     live value when the recommendation was produced
 * @param recommendedValue proposed value; equals {@code currentValue} for {@link Action#HOLD}
 * @param reason           plain-language explanation
 * @param evidence         signal name to observed value (already formatted with units)
 * @param confidence       how strongly the evidence supports the action
 * @param relatedRuleIds   static rules worth checking alongside (e.g. leak detection)
 * @param createdAt        when it was produced
 */
public record Recommendation(
        String ruleId,
        String knobId,
        Action action,
        long currentValue,
        long recommendedValue,
        String reason,
        Map<String, String> evidence,
        Confidence confidence,
        List<String> relatedRuleIds,
        Instant createdAt) {

    public Recommendation {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(knobId, "knobId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(createdAt, "createdAt");
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        relatedRuleIds = List.copyOf(relatedRuleIds);
        if (action == Action.HOLD && currentValue != recommendedValue) {
            throw new IllegalArgumentException("HOLD must keep the current value");
        }
        if (action == Action.INCREASE && recommendedValue <= currentValue) {
            throw new IllegalArgumentException("INCREASE must propose a larger value");
        }
        if (action == Action.DECREASE && recommendedValue >= currentValue) {
            throw new IllegalArgumentException("DECREASE must propose a smaller value");
        }
    }

    public enum Action {
        INCREASE,
        DECREASE,
        /** Do not change this knob; the evidence points at a different root cause. */
        HOLD
    }

    public enum Confidence {
        LOW,
        MEDIUM,
        HIGH
    }
}
