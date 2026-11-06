package io.github.winkytek.springtune.boot;

import java.time.Instant;

/**
 * One observation of a HikariCP pool. Timer values are cumulative (as exposed by Micrometer); means over a
 * window are computed from the difference between the first and last sample.
 *
 * @param timeouts cumulative {@code hikaricp.connections.timeout} count (0 when the metric is unavailable)
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
        double usageTotalMs,
        double timeouts) implements SampleWindow.Timed {

    HikariSample(Instant at, int active, int idle, int pending, int maximumPoolSize, boolean timersAvailable,
            long acquireCount, double acquireTotalMs, long usageCount, double usageTotalMs) {
        this(at, active, idle, pending, maximumPoolSize, timersAvailable, acquireCount, acquireTotalMs, usageCount,
                usageTotalMs, 0);
    }

    /**
     * Threads wait while the pool holds fewer connections than its maximum: it has room but could not open
     * (or re-open) connections, so the database is refusing or failing them.
     */
    boolean starved() {
        return pending > 0 && active + idle < maximumPoolSize;
    }
}
