package io.github.winkytek.springtune.boot;

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
    List<S> covering(Instant now, Duration window, Duration tolerance) {
        return coverage(now, window, tolerance).samples();
    }

    /**
     * What {@link #covering} decides, and how far the buffer is from covering the window: the evaluation status
     * {@code COLLECTING} reports these numbers, so status and recommendation come from the same check.
     */
    synchronized Coverage<S> coverage(Instant now, Duration window, Duration tolerance) {
        Instant from = now.minus(window);
        List<S> inWindow = new ArrayList<>();
        for (S sample : samples) {
            if (!sample.at().isBefore(from) && !sample.at().isAfter(now)) {
                inWindow.add(sample);
            }
        }
        Duration needed = window.minus(tolerance).isNegative() ? Duration.ZERO : window.minus(tolerance);
        if (samples.isEmpty()) {
            return new Coverage<>(List.of(), 0, Duration.ZERO, needed);
        }
        Duration seen = Duration.between(samples.peekFirst().at(), now);
        seen = seen.isNegative() ? Duration.ZERO : seen.compareTo(window) > 0 ? window : seen;
        boolean covered = !samples.peekFirst().at().isAfter(now.minus(window).plus(tolerance))
                && inWindow.size() >= Coverage.MIN_SAMPLES;
        return new Coverage<>(covered ? List.copyOf(inWindow) : List.of(), inWindow.size(), seen, needed);
    }

    /**
     * @param samples     the samples covering the window, or empty when it is not covered yet
     * @param seenSamples samples retained within the window
     * @param seen        time from the oldest retained sample to now, at most the window
     * @param needed      time the oldest sample must reach back ({@code window - tolerance})
     */
    record Coverage<S>(List<S> samples, int seenSamples, Duration seen, Duration needed) {

        static final int MIN_SAMPLES = 3;

        /** A coverage decided by the caller (tests): covered when {@code samples} is not empty. */
        static <S> Coverage<S> of(List<S> samples, Duration window) {
            return new Coverage<>(samples, samples.size(), samples.isEmpty() ? Duration.ZERO : window, window);
        }

        boolean covered() {
            return !samples.isEmpty();
        }
    }

    synchronized S latest() {
        return samples.peekLast();
    }

    synchronized int size() {
        return samples.size();
    }

    /** Latest sample taken at or before {@code at}, or {@code null}. */
    synchronized S atOrBefore(Instant at) {
        S result = null;
        for (S sample : samples) {
            if (sample.at().isAfter(at)) {
                break;
            }
            result = sample;
        }
        return result;
    }

    /** Earliest sample taken at or after {@code at}, or {@code null}. */
    synchronized S firstAtOrAfter(Instant at) {
        for (S sample : samples) {
            if (!sample.at().isBefore(at)) {
                return sample;
            }
        }
        return null;
    }

    static <S> double ratio(List<S> samples, Function<S, Boolean> predicate) {
        if (samples.isEmpty()) {
            return 0;
        }
        return (double) samples.stream().filter(predicate::apply).count() / samples.size();
    }
}
