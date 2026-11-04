package io.github.winky.springtune.boot;

import java.util.Optional;

/**
 * Reads <b>cumulative</b> meter totals by name and pool tag. Decouples components from Micrometer.
 *
 * <p>Means and rates over a window are computed from the difference between two reads, so the values must
 * only ever grow. Micrometer's cumulative registries (Prometheus, {@code SimpleMeterRegistry} in its default
 * mode) behave like that; a {@code StepMeterRegistry} (Datadog, New Relic, Elastic, ...) reports only the
 * last step. {@link MicrometerSupport} therefore reads from a cumulative child of a composite registry and
 * reports nothing when only step registries exist. Registries that are push-based but not
 * {@code StepMeterRegistry} subclasses (for example OTLP with delta temporality, or a
 * {@code SimpleMeterRegistry} in {@code STEP} mode) cannot be detected and give wrong deltas.
 */
@FunctionalInterface
interface TimerReader {

    TimerReader NONE = (name, pool) -> Optional.empty();

    Optional<Totals> read(String name, String pool);

    /** Cumulative count of a counter, e.g. {@code hikaricp.connections.timeout}. */
    default Optional<Double> counter(String name, String pool) {
        return Optional.empty();
    }

    /** Cumulative totals of {@code http.server.requests} over all URIs, recorded as evidence only. */
    default Optional<HttpTotals> http() {
        return Optional.empty();
    }

    record Totals(long count, double totalMs) {
    }

    record HttpTotals(long requests, double totalMs, long serverErrors) {
    }
}
