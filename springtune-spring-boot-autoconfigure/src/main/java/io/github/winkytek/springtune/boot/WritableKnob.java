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
 * @param serverLimit     the database server's connection limit (Hikari), checked before a pool is raised
 */
record WritableKnob(String knobId, String componentId, Kind kind, long configuredValue, KnobWriter writer,
        Supplier<Map<String, Double>> counters, Supplier<ServerConnectionLimit> serverLimit) {

    WritableKnob(String knobId, String componentId, Kind kind, long configuredValue, KnobWriter writer,
            Supplier<Map<String, Double>> counters) {
        this(knobId, componentId, kind, configuredValue, writer, counters, ServerConnectionLimit::notApplicable);
    }

    enum Kind {
        HIKARI,
        EXECUTOR
    }

    long current() {
        return writer.read().get(writer.setting());
    }
}
