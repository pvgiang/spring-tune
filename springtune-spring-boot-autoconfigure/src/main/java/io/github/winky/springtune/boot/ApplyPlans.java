package io.github.winky.springtune.boot;

import io.github.winky.springtune.model.Recommendation;
import io.github.winky.springtune.model.Recommendation.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds {@link ApplyPlan}s. Pure functions. Property names are only given where they are certain: Boot's
 * auto-configured {@code dataSource} bean binds {@code spring.datasource.hikari.*} and its
 * {@code applicationTaskExecutor} binds {@code spring.task.execution.pool.*}. For any other bean the plan
 * names the bean and pool and says the prefix is application-specific.
 */
final class ApplyPlans {

    static final String PRIMARY_DATASOURCE = "dataSource";
    static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

    private ApplyPlans() {
    }

    static Optional<ApplyPlan> hikari(Recommendation rec, String beanName, String poolName, int minimumIdle,
            SpringTuneProperties.Budget budget, Optional<Integer> budgetCap) {
        if (rec.action() == Action.HOLD) {
            return Optional.empty();
        }
        long from = rec.currentValue();
        long to = rec.recommendedValue();
        boolean minIdleMoves = minimumIdle == from || minimumIdle > to;
        boolean primary = PRIMARY_DATASOURCE.equals(beanName);
        List<String> commit = new ArrayList<>();
        if (primary) {
            commit.add("spring.datasource.hikari.maximum-pool-size=" + to);
            if (minIdleMoves) {
                commit.add("spring.datasource.hikari.minimum-idle=" + to);
            }
        } else {
            commit.add("maximumPoolSize=" + to + (minIdleMoves ? " and minimumIdle=" + to : "")
                    + " on DataSource bean '" + beanName + "' (pool '" + poolName + "'), wherever it is configured:"
                    + " SpringTune cannot know its property prefix.");
        }

        List<String> arithmetic = new ArrayList<>();
        if (rec.action() == Action.INCREASE) {
            if (budgetCap.isPresent()) {
                int usable = (int) Math.floor(budget.getDatabaseMaxConnections() * budget.getSafetyFactor());
                arithmetic.add("per-instance cap = floor(database-max-connections x safety-factor / max-replicas)"
                        + " = floor(" + budget.getDatabaseMaxConnections() + " x " + budget.getSafetyFactor() + " / "
                        + budget.getMaxReplicas() + ") = " + budgetCap.get());
                arithmetic.add(to + (to <= budgetCap.get() ? " <= " : " > ") + "cap " + budgetCap.get()
                        + (to <= budgetCap.get() ? ": within budget" : ": above budget"));
                arithmetic.add("fleet at max replicas: " + to + " x " + budget.getMaxReplicas() + " = "
                        + to * budget.getMaxReplicas() + " of " + usable + " usable connections (this service only)");
            } else {
                arithmetic.add("No connection budget configured (springtune.budget.*): apply mode refuses to raise"
                        + " maximumPoolSize. Check that max_connections covers " + to + " x replicas plus every other"
                        + " client of the database.");
            }
        } else if (budget.getMaxReplicas() != null) {
            arithmetic.add("frees " + (from - to) + " connections per instance, " + (from - to) * budget.getMaxReplicas()
                    + " at " + budget.getMaxReplicas() + " replicas");
        }

        List<String> sideEffects = new ArrayList<>();
        if (minimumIdle == from) {
            sideEffects.add("minimumIdle equals maximumPoolSize (" + from + ", a fixed-size pool, Hikari's default):"
                    + " it moves to " + to + " as well, otherwise the pool would silently become dynamic.");
        } else if (minimumIdle > to) {
            sideEffects.add("minimumIdle (" + minimumIdle + ") is above the new maximum: it is lowered to " + to + ".");
        }
        if (rec.action() == Action.INCREASE) {
            sideEffects.add("Hikari opens the additional connections on demand, as threads wait for one.");
        } else {
            sideEffects.add("Hikari shrinks lazily: connections above the new maximum are retired as they idle out"
                    + " (idleTimeout, only while minimumIdle < maximumPoolSize) or reach maxLifetime, not closed"
                    + " at once.");
        }
        return Optional.of(new ApplyPlan(rec.ruleId(), rec.knobId(),
                "DataSource bean '" + beanName + "' (Hikari pool '" + poolName + "')", from, to, commit, arithmetic,
                sideEffects, ApplyPlan.EPHEMERAL));
    }

    static Optional<ApplyPlan> executor(Recommendation rec, String beanName, String setting, int corePoolSize,
            int maxPoolSize, boolean unboundedQueue) {
        if (rec.action() == Action.HOLD) {
            return Optional.empty();
        }
        long to = rec.recommendedValue();
        boolean core = ExecutorPoolWriter.CORE.equals(setting);
        boolean maxMoves = core && to > maxPoolSize;
        boolean coreMoves = !core && to < corePoolSize;
        List<String> commit = new ArrayList<>();
        if (APPLICATION_TASK_EXECUTOR.equals(beanName)) {
            commit.add("spring.task.execution.pool." + (core ? "core-size=" : "max-size=") + to);
            if (maxMoves) {
                commit.add("spring.task.execution.pool.max-size=" + to);
            }
            if (coreMoves) {
                commit.add("spring.task.execution.pool.core-size=" + to);
            }
        } else {
            commit.add(setting + "=" + to + (maxMoves ? " and maxPoolSize=" + to : "")
                    + (coreMoves ? " and corePoolSize=" + to : "") + " on ThreadPoolTaskExecutor bean '" + beanName
                    + "', wherever it is configured: SpringTune cannot know its property prefix.");
        }
        List<String> sideEffects = new ArrayList<>();
        if (maxMoves) {
            sideEffects.add("corePoolSize may not exceed maxPoolSize (" + maxPoolSize + "): maxPoolSize moves to " + to
                    + " first.");
        }
        if (coreMoves) {
            sideEffects.add("maxPoolSize may not drop below corePoolSize (" + corePoolSize + "): corePoolSize moves to "
                    + to + " first.");
        }
        if (core && unboundedQueue) {
            sideEffects.add("The queue is unbounded, so the pool never grows past corePoolSize: additional threads"
                    + " start as tasks arrive. Bounding the queue is the lasting fix (ST-ASYNC-001).");
        } else if (!core) {
            sideEffects.add("Threads above corePoolSize start only while the queue is full and stop after"
                    + " keepAlive.");
        }
        return Optional.of(new ApplyPlan(rec.ruleId(), rec.knobId(), "ThreadPoolTaskExecutor bean '" + beanName + "'",
                rec.currentValue(), to, commit, List.of(), sideEffects, ApplyPlan.EPHEMERAL));
    }
}
