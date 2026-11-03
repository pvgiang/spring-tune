package io.github.pvgiang.springtune.boot;

import io.github.pvgiang.springtune.model.Knob;
import io.github.pvgiang.springtune.model.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Payload of {@code GET /actuator/tuning}. */
public record TuningSnapshot(
        String mode,
        boolean applySupported,
        Instant generatedAt,
        Budget budget,
        List<Component> components,
        List<Recommendation> recommendations) {

    public record Component(String id, int samples, List<Knob> knobs, Map<String, Object> signals) {
    }

    /**
     * @param perInstanceCap upper bound for maximumPoolSize recommendations, or {@code null} when not configured
     */
    public record Budget(Integer databaseMaxConnections, Integer maxReplicas, double safetyFactor,
            Integer perInstanceCap) {
    }
}
