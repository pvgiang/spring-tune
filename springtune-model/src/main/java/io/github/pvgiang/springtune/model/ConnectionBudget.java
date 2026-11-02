package io.github.pvgiang.springtune.model;

import java.util.List;
import java.util.Objects;

/**
 * The number of connections a database can safely hand out to application pools.
 *
 * <p>Formula (identical in the CI check and the runtime starter):
 * <pre>
 *   usable          = floor(databaseMaxConnections × safetyFactor)
 *   demand          = Σ (maximumPoolSize × maxReplicas) over every service using the database
 *   exceeded        = demand &gt; usable
 *   perInstanceCap  = floor(usable / maxReplicas)        (single service owning the budget)
 * </pre>
 * The safety factor keeps headroom for rolling-update surge pods, admin sessions and migrations.
 *
 * @param databaseMaxConnections the database's {@code max_connections} (or equivalent)
 * @param safetyFactor           fraction of {@code max_connections} that pools may use, in (0, 1]
 */
public record ConnectionBudget(int databaseMaxConnections, double safetyFactor) {

    public static final double DEFAULT_SAFETY_FACTOR = 0.8;

    public ConnectionBudget {
        if (databaseMaxConnections < 1) {
            throw new IllegalArgumentException("databaseMaxConnections must be >= 1 but was " + databaseMaxConnections);
        }
        if (!(safetyFactor > 0.0 && safetyFactor <= 1.0)) {
            throw new IllegalArgumentException("safetyFactor must be in (0, 1] but was " + safetyFactor);
        }
    }

    public int usableConnections() {
        return (int) Math.floor(databaseMaxConnections * safetyFactor);
    }

    /** Largest pool size one instance may use when {@code maxReplicas} instances share the whole budget. */
    public int perInstanceCap(int maxReplicas) {
        if (maxReplicas < 1) {
            throw new IllegalArgumentException("maxReplicas must be >= 1 but was " + maxReplicas);
        }
        return usableConnections() / maxReplicas;
    }

    public Evaluation evaluate(List<Consumer> consumers) {
        long demand = consumers.stream().mapToLong(Consumer::demand).sum();
        return new Evaluation(this, List.copyOf(consumers), demand, demand > usableConnections());
    }

    /** One service drawing on the budget. */
    public record Consumer(String service, int maximumPoolSize, int maxReplicas) {
        public Consumer {
            Objects.requireNonNull(service, "service");
            if (maximumPoolSize < 1 || maxReplicas < 1) {
                throw new IllegalArgumentException("maximumPoolSize and maxReplicas must be >= 1");
            }
        }

        public long demand() {
            return (long) maximumPoolSize * maxReplicas;
        }
    }

    public record Evaluation(ConnectionBudget budget, List<Consumer> consumers, long demand, boolean exceeded) {
    }
}
