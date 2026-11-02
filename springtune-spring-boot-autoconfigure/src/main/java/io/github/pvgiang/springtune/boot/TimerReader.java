package io.github.pvgiang.springtune.boot;

import java.util.Optional;

/** Reads cumulative timer totals by name and pool tag. Decouples components from Micrometer. */
@FunctionalInterface
interface TimerReader {

    TimerReader NONE = (name, pool) -> Optional.empty();

    Optional<Totals> read(String name, String pool);

    record Totals(long count, double totalMs) {
    }
}
