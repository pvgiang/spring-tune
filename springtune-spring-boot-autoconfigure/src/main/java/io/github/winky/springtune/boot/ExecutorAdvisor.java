package io.github.winky.springtune.boot;

import io.github.winky.springtune.model.Recommendation;
import io.github.winky.springtune.model.Recommendation.Action;
import io.github.winky.springtune.model.Recommendation.Confidence;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>ST-REC-EXEC-001</b>: tasks queue while every thread is busy in at least {@code backlogRatio} of the
 * window. With an unbounded queue the pool never grows past its core size, so the core size is the knob
 * (and the queue should be bounded, see ST-ASYNC-001/002). With a bounded queue and the pool at its
 * maximum, the maximum is the knob.
 */
final class ExecutorAdvisor {

    static final String RULE = "ST-REC-EXEC-001";

    private ExecutorAdvisor() {
    }

    static Optional<Recommendation> advise(String knobPrefix, List<ExecutorSample> window,
            SpringTuneProperties.Executor settings, Instant now) {
        if (window.isEmpty()) {
            return Optional.empty();
        }
        double backlog = SampleWindow.ratio(window, s -> s.queued() > 0 && s.active() >= s.poolSize());
        if (backlog < settings.getBacklogRatio()) {
            return Optional.empty();
        }
        ExecutorSample last = window.get(window.size() - 1);
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("window", Format.duration(settings.getWindow()));
        evidence.put("samples", String.valueOf(window.size()));
        evidence.put("samplesWithBacklogAndAllThreadsBusy", Format.percent(backlog));
        evidence.put("maxQueued", String.valueOf(window.stream().mapToInt(ExecutorSample::queued).max().orElse(0)));
        evidence.put("poolSize", last.poolSize() + " (core " + last.corePoolSize() + ", max " + last.maxPoolSize() + ")");
        boolean unbounded = last.queueCapacity() == Integer.MAX_VALUE;
        evidence.put("queueCapacity", unbounded ? "unbounded" : String.valueOf(last.queueCapacity()));
        String caveat = " More threads help only if tasks are not waiting on a saturated downstream (database pool,"
                + " remote service); check those signals first.";
        if (unbounded) {
            int proposed = Format.step(last.corePoolSize(), settings.getStep());
            return Optional.of(new Recommendation(RULE, knobPrefix + ".corePoolSize", Action.INCREASE,
                    last.corePoolSize(), proposed,
                    "Tasks waited in the queue while all " + last.poolSize() + " threads were busy in "
                            + Format.percent(backlog) + " of samples. The queue is unbounded, so the pool never grows"
                            + " beyond corePoolSize (maxPoolSize has no effect): raise corePoolSize to " + proposed
                            + " and bound the queue." + caveat,
                    evidence, Confidence.MEDIUM, List.of("ST-ASYNC-001", "ST-ASYNC-002"), now));
        }
        if (last.poolSize() >= last.maxPoolSize()) {
            int proposed = Format.step(last.maxPoolSize(), settings.getStep());
            return Optional.of(new Recommendation(RULE, knobPrefix + ".maxPoolSize", Action.INCREASE,
                    last.maxPoolSize(), proposed,
                    "Tasks waited in the queue while the pool was at its maximum of " + last.maxPoolSize()
                            + " threads in " + Format.percent(backlog) + " of samples: raise maxPoolSize to " + proposed
                            + "." + caveat,
                    evidence, Confidence.MEDIUM, List.of("ST-ASYNC-002"), now));
        }
        return Optional.empty();
    }
}
