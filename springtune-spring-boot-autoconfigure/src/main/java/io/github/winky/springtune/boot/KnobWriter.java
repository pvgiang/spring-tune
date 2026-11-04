package io.github.winky.springtune.boot;

import java.util.Map;

/**
 * Writes one knob (and the settings that must move with it). <b>Implementations are the only classes allowed
 * to call a pool or executor size setter</b>; {@code WriterIsolationTest} enforces this on the sources. Only
 * {@link ChangeController} calls a writer, and only after {@link ApplyGuard} approved the change.
 */
interface KnobWriter {

    /** The primary setting, e.g. {@code maximumPoolSize}. It is always the first key of every map. */
    String setting();

    /** Live values of every setting this writer may change, primary first. */
    Map<String, Long> read();

    /** Values to write to move the primary setting to {@code target}, including linked settings. Pure. */
    Map<String, Long> plan(long target);

    /**
     * Writes {@code values} (keys as returned by {@link #plan}) in an order that keeps the component valid at
     * every step. Throws when the component rejects a value.
     */
    void write(Map<String, Long> values);
}
