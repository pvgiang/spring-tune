package io.github.winkytek.springtune.boot;

import java.time.Instant;

/**
 * One observation of a HikariCP pool. Timer values are cumulative (as exposed by Micrometer); means over a
 * window are computed from the difference between the first and last sample.
 *
 * @param timeouts cumulative {@code hikaricp.connections.timeout} count (0 when the metric is unavailable)
 * @param total    connections the pool holds ({@code HikariPoolMXBean.getTotalConnections()}, one read of the pool's
 *                 connection list). Not {@code active + idle}: HikariCP counts those in two separate scans, and a
 *                 connection handed from one thread to the next between the scans is in neither, so under
 *                 saturation {@code active + idle} reads below the true size while the pool is full.
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
        double timeouts,
        int total) implements SampleWindow.Timed {

    HikariSample(Instant at, int active, int idle, int pending, int maximumPoolSize, boolean timersAvailable,
            long acquireCount, double acquireTotalMs, long usageCount, double usageTotalMs, double timeouts) {
        this(at, active, idle, pending, maximumPoolSize, timersAvailable, acquireCount, acquireTotalMs, usageCount,
                usageTotalMs, timeouts, active + idle);
    }

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
        return pending > 0 && total < maximumPoolSize;
    }
}
