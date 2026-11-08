package io.github.winkytek.springtune.loadtests;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.winkytek.springtune.loadtests.Replica.ApplyResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** Several replicas of one service (applications {@code replica-1..n}), each with its own role and load. */
final class Fleet {

    static final Predicate<String> SERVICE = app -> app.startsWith("replica-");

    final List<Replica> replicas = new ArrayList<>();
    final List<OpenLoopJdbcLoad> loads = new ArrayList<>();
    final List<String> roles = new ArrayList<>();

    /**
     * Starts {@code n} replicas with {@code settings}, each saturated by an open-loop load of 200 requests/s on 12
     * workers (pool 4: ~133 requests/s of capacity at 30 ms) running {@code workload.apply(i)}.
     */
    static Fleet start(Scenario scenario, int n, Map<String, String> settings,
            IntFunction<OpenLoopJdbcLoad.Request> workload) throws Exception {
        Fleet fleet = new Fleet();
        for (int i = 1; i <= n; i++) {
            String role = scenario.role("r" + i, -1);
            fleet.roles.add(role);
            fleet.replicas.add(scenario.replica("replica-" + i, role, settings));
        }
        for (int i = 1; i <= n; i++) {
            Replica replica = fleet.replicas.get(i - 1);
            fleet.loads.add(scenario.load(OpenLoopJdbcLoad.on(replica.name(), replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(workload.apply(i))));
        }
        return fleet;
    }

    /**
     * Every replica's operator waits for a recommendation matching {@code ready}, then all of them POST the apply in
     * the same instant (a {@link CyclicBarrier}).
     */
    List<ApplyResult> applyAtOnce(Predicate<JsonNode> ready, String what) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(replicas.size());
        ExecutorService operators = Executors.newFixedThreadPool(replicas.size());
        try {
            List<Future<ApplyResult>> futures = new ArrayList<>();
            for (Replica replica : replicas) {
                futures.add(operators.submit(() -> {
                    JsonNode rec = Operator.awaitRecommendation(replica, ready, what);
                    barrier.await(Operator.RECOMMENDATION_CEILING.toSeconds(), TimeUnit.SECONDS);
                    return replica.apply(rec, "load test: all replicas at once");
                }));
            }
            List<ApplyResult> results = new ArrayList<>();
            for (Future<ApplyResult> future : futures) {
                results.add(future.get(2 * Operator.RECOMMENDATION_CEILING.toSeconds(), TimeUnit.SECONDS));
            }
            return results;
        } finally {
            operators.shutdownNow();
        }
    }

    /** Final outcome per replica of the changes {@code results} started (null for refused requests). */
    List<JsonNode> awaitFinal(List<ApplyResult> results) {
        List<JsonNode> entries = new ArrayList<>();
        for (int i = 0; i < replicas.size(); i++) {
            ApplyResult result = results.get(i);
            entries.add(result.status() == 200 ? Operator.awaitFinal(replicas.get(i), result.changeId()) : null);
        }
        return entries;
    }

    /**
     * Waits until the normal cooldown is over on every replica (state IDLE again); a reverted knob stays locked for
     * cooldown-after-revert (10 minutes here) and is not waited for.
     */
    void awaitCooldownOver() {
        for (Replica replica : replicas) {
            await(replica.name() + ": cooldown over").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions().until(() -> {
                        String state = replica.knobState();
                        return "IDLE".equals(state) || "REVERTED".equals(state);
                    });
        }
    }

    long clientSqlState(String state) {
        return loads.stream().mapToLong(l -> l.stats().sqlState(state)).sum();
    }

    String loadStats() {
        StringBuilder text = new StringBuilder();
        loads.forEach(l -> text.append(l.name()).append(": ").append(l.stats()).append("; "));
        return text.toString();
    }
}
