package io.github.pvgiang.springtune.boot;

import java.time.Instant;

/**
 * One observation of a HikariCP pool. Timer values are cumulative (as exposed by Micrometer); means over a
 * window are computed from the difference between the first and last sample.
 */
record HikariSample(
        Instant at,
        int active,
        int idle,
        int pending,
        int maximumPoolSize,
        boolean timersAvailable,
        long acquireCount,
        double acquireTotalMs,
        long usageCount,
        double usageTotalMs) implements SampleWindow.Timed {
}
