package io.github.winky.springtune.boot;

import io.github.winky.springtune.model.Knob;
import io.github.winky.springtune.model.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A runtime component with tunable knobs, live signals and (in ADVISE/APPLY mode) recommendations. Read-only:
 * changes go through a {@link KnobWriter} owned by apply mode, never through this interface.
 */
public interface TunedComponent {

    /** Stable id, e.g. {@code hikari:orders-pool} or {@code executor:applicationTaskExecutor}. */
    String id();

    List<Knob> knobs();

    /** Latest signal values for display. */
    Map<String, Object> signals();

    int sampleCount();

    /** Records one sample at {@code now}. Must never throw for a component that is not started yet. */
    void sample(Instant now);

    List<Recommendation> recommend(Instant now);

    /** How to apply {@code recommendation} if it concerns one of this component's knobs. */
    default Optional<ApplyPlan> applyPlan(Recommendation recommendation) {
        return Optional.empty();
    }
}
