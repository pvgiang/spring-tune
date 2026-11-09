package io.github.winkytek.springtune.boot;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A knob that apply mode may change, with its writer and the cumulative counters of its <b>own</b> component
 * that verification compares (keys in {@link Verification}).
 *
 * @param knobId          e.g. {@code hikari.orders.maximumPoolSize}
 * @param componentId     e.g. {@code hikari:orders}
 * @param kind            which verification rules apply
 * @param configuredValue value when SpringTune discovered the component (the startup configuration)
 * @param writer          the only path to the setter
 * @param counters        cumulative counters, read on every sampler tick; empty when unavailable
 * @param serverLimit     the database server's connection limit (Hikari), <b>re-read</b> when called: once per apply
 *                        attempt, before a pool may be raised. It may block (bounded by the pool's
 *                        {@code connectionTimeout}), so {@link ChangeController} calls it outside its lock, never on
 *                        the sampler thread
 * @param capacity        the capacity the component actually holds right now (Hikari: total connections; executor:
 *                        threads), read on every sampler tick while a change is verified ({@code capacityObtained});
 *                        {@code null} when unknown
 */
record WritableKnob(String knobId, String componentId, Kind kind, long configuredValue, KnobWriter writer,
        Supplier<Map<String, Double>> counters, Supplier<ServerConnectionLimit> serverLimit,
        Supplier<Capacity> capacity) {

    WritableKnob(String knobId, String componentId, Kind kind, long configuredValue, KnobWriter writer,
            Supplier<Map<String, Double>> counters) {
        this(knobId, componentId, kind, configuredValue, writer, counters, ServerConnectionLimit::notApplicable,
                () -> null);
    }

    WritableKnob(String knobId, String componentId, Kind kind, long configuredValue, KnobWriter writer,
            Supplier<Map<String, Double>> counters, Supplier<ServerConnectionLimit> serverLimit) {
        this(knobId, componentId, kind, configuredValue, writer, counters, serverLimit, () -> null);
    }

    enum Kind {
        HIKARI,
        EXECUTOR
    }

    /**
     * What the component holds at one instant.
     *
     * @param held   connections (Hikari {@code getTotalConnections()}) or threads ({@code getPoolSize()})
     * @param demand whether work was waiting for more: threads awaiting a connection, or queued tasks
     */
    record Capacity(long held, boolean demand) {
    }

    long current() {
        return writer.read().get(writer.setting());
    }
}
