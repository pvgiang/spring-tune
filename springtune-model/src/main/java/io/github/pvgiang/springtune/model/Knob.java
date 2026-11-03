package io.github.pvgiang.springtune.model;

import java.util.Objects;

/**
 * A tunable runtime setting and its live value.
 *
 * @param id           stable id, e.g. {@code hikari.orders.maximumPoolSize}
 * @param component    owning component, e.g. {@code hikari:orders} or {@code executor:applicationTaskExecutor}
 * @param name         setting name within the component, e.g. {@code maximumPoolSize}
 * @param currentValue live value
 * @param defaultValue library default, or {@code null} when unknown
 * @param min          lower bound a recommendation may propose, or {@code null}
 * @param max          upper bound a recommendation may propose, or {@code null}
 * @param unit         {@code count} or {@code ms}
 * @param source       where the value is read from, e.g. {@code HikariConfigMXBean}
 */
public record Knob(
        String id,
        String component,
        String name,
        long currentValue,
        Long defaultValue,
        Long min,
        Long max,
        String unit,
        String source) {

    public Knob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(source, "source");
    }
}
