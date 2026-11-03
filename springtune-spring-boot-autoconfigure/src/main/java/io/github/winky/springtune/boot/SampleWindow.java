package io.github.winky.springtune.boot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Bounded, time-ordered buffer of samples. Thread-safe: written by the sampler thread, read by the
 * actuator endpoint.
 */
final class SampleWindow<S extends SampleWindow.Timed> {

    interface Timed {
        Instant at();
    }

    private final Deque<S> samples = new ArrayDeque<>();
    private final Duration retention;

    SampleWindow(Duration retention) {
        this.retention = retention;
    }

    synchronized void add(S sample) {
        samples.addLast(sample);
        Instant cutoff = sample.at().minus(retention);
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    /**
     * Samples within {@code window} before {@code now}, or empty when the buffer does not yet cover the
     * window (oldest retained sample is younger than {@code window - tolerance}). Recommendations are never
     * made on partial evidence.
     */
    synchronized List<S> covering(Instant now, Duration window, Duration tolerance) {
        if (samples.isEmpty() || samples.peekFirst().at().isAfter(now.minus(window).plus(tolerance))) {
            return List.of();
        }
        Instant from = now.minus(window);
        List<S> result = new ArrayList<>();
        for (S sample : samples) {
            if (!sample.at().isBefore(from) && !sample.at().isAfter(now)) {
                result.add(sample);
            }
        }
        return result.size() >= 3 ? result : List.of();
    }

    synchronized S latest() {
        return samples.peekLast();
    }

    synchronized int size() {
        return samples.size();
    }

    static <S> double ratio(List<S> samples, Function<S, Boolean> predicate) {
        if (samples.isEmpty()) {
            return 0;
        }
        return (double) samples.stream().filter(predicate::apply).count() / samples.size();
    }
}
