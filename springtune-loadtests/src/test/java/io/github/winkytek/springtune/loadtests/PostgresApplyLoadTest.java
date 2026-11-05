package io.github.winkytek.springtune.loadtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.winkytek.springtune.loadtests.Replica.ApplyResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Apply mode on one replica against a real PostgreSQL: a raise that helps is confirmed, slow SQL is held, and a raise
 * that makes the database slower is reverted once and not retried (bar 4: "one revert with no oscillation").
 * Assertions are counts, verdicts, outcome and refusal codes, server-log lines and {@code pg_stat_activity} only.
 */
@RequiresPostgres
class PostgresApplyLoadTest {

    static final String RAISE = "ST-REC-HIKARI-001";
    static final String HOLD_SLOW = "ST-REC-HIKARI-002";

    @Test
    void raiseIsConfirmedAndTheServerNeverSeesMoreSessionsThanTheNewMaximum() throws Exception {
        try (Scenario scenario = new Scenario("PostgresApplyLoadTest.raiseConfirmed")) {
            ActivityProbe probe = scenario.probe();
            String role = scenario.role("r1", -1);
            Replica replica = scenario.replica("replica-1", role, Replica.defaults(4, 20, 3));
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(Workloads.sleep(0.03)));

            JsonNode rec = Operator.awaitAnyRecommendation(replica);
            assertThat(rec.path("ruleId").asText()).as(rec.toString()).isEqualTo(RAISE);
            assertThat(rec.path("action").asText()).isEqualTo("INCREASE");
            assertThat(rec.path("recommendedValue").asInt()).isEqualTo(5);
            int maxBefore = probe.maxOf("replica-1");

            ApplyResult applied = replica.apply(rec, "load test: pool saturated, 30 ms holds");
            assertThat(applied.status()).as(applied.body().toString()).isEqualTo(200);
            assertThat(applied.outcome()).isEqualTo("VERIFYING");
            JsonNode entry = Operator.awaitFinal(replica, applied.changeId());
            probe.awaitReadings(3);

            scenario.record("load " + load.stats());
            scenario.record("server sessions replica-1: max before apply " + maxBefore + ", max overall "
                    + probe.maxOf("replica-1") + " (" + probe.count() + " readings); max client backends "
                    + probe.maxTotal());
            scenario.record("outcome " + entry.path("outcome").asText() + " verdict " + entry.path("verdict").asText()
                    + " reasons " + entry.path("reasons") + " evidence " + entry.path("evidence"));
            ServerLog log = scenario.serverLog();
            scenario.record("server log: too-many-clients lines " + log.count(ServerLog.TOO_MANY_CLIENTS)
                    + ", authorized connections replica role " + log.authorizedConnections(role));

            assertThat(entry.path("outcome").asText()).as(entry.toString()).isEqualTo("CONFIRMED");
            assertThat(entry.path("verdict").asText()).isEqualTo("OK");
            assertThat(replica.maximumPoolSize()).isEqualTo(5);
            assertThat(maxBefore).as("server-side sessions before the raise").isLessThanOrEqualTo(4);
            assertThat(probe.maxOf("replica-1")).as("server-side sessions never above the new maximum")
                    .isEqualTo(5);
            assertThat(log.count(ServerLog.TOO_MANY_CLIENTS)).isZero();
            assertThat(load.stats().sqlStates()).as("client errors").isEmpty();
        }
    }

    @Test
    void slowSqlIsHeldAndApplyIsRefused() throws Exception {
        try (Scenario scenario = new Scenario("PostgresApplyLoadTest.slowSqlHeld")) {
            ActivityProbe probe = scenario.probe();
            String role = scenario.role("r1", -1);
            Replica replica = scenario.replica("replica-1", role, Replica.defaults(4, 20, 3));
            // 600 ms in the database per request, above slow-usage (500 ms).
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(10)
                    .workers(8).queue(50).start(Workloads.sleep(0.6)));

            JsonNode rec = Operator.awaitAnyRecommendation(replica);
            ApplyResult hold = replica.apply(rec, "load test: try to apply a HOLD");
            ApplyResult raise = replica.apply(RAISE, 4, 5, "load test: try to raise anyway");
            List<String> outcomes = new ArrayList<>();
            replica.audit().forEach(e -> outcomes.add(e.path("outcome").asText() + "/" + e.path("code").asText()));

            scenario.record("load " + load.stats());
            scenario.record("recommendation " + rec);
            scenario.record("apply HOLD -> " + hold.status() + " " + hold.code() + "; apply " + RAISE + " -> "
                    + raise.status() + " " + raise.code());
            scenario.record("server sessions replica-1 max " + probe.maxOf("replica-1") + " (" + probe.count()
                    + " readings)");

            assertThat(rec.path("ruleId").asText()).as(rec.toString()).isEqualTo(HOLD_SLOW);
            assertThat(rec.path("action").asText()).isEqualTo("HOLD");
            assertThat(hold.status()).isEqualTo(409);
            assertThat(hold.code()).isEqualTo("RECOMMENDATION_IS_HOLD");
            assertThat(raise.status()).isEqualTo(409);
            assertThat(raise.code()).isEqualTo("NO_CURRENT_RECOMMENDATION");
            assertThat(outcomes).containsOnly("REFUSED/RECOMMENDATION_IS_HOLD", "REFUSED/NO_CURRENT_RECOMMENDATION");
            assertThat(replica.maximumPoolSize()).isEqualTo(4);
            assertThat(probe.maxOf("replica-1")).isLessThanOrEqualTo(4);
            assertThat(scenario.serverLog().count(ServerLog.TOO_MANY_CLIENTS)).isZero();
        }
    }

    @Test
    void raiseThatSlowsEverySessionIsRevertedOnceAndNotRetried() throws Exception {
        try (Scenario scenario = new Scenario("PostgresApplyLoadTest.revertNoOscillation")) {
            ActivityProbe probe = scenario.probe();
            String role = scenario.role("r1", -1);
            Replica replica = scenario.replica("replica-1", role, Replica.defaults(4, 20, 3));
            // The server decides: 30 ms per request while at most 4 sessions of this application are active, 60 ms
            // with more.
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(Workloads.slowerAbove(4, 0.03, 0.06)));

            JsonNode rec = Operator.awaitAnyRecommendation(replica);
            assertThat(rec.path("action").asText()).as(rec.toString()).isEqualTo("INCREASE");
            ApplyResult applied = replica.apply(rec, "load test: pool saturated");
            assertThat(applied.status()).as(applied.body().toString()).isEqualTo(200);
            JsonNode entry = Operator.awaitFinal(replica, applied.changeId());
            int sessionsAtRevert = probe.maxOf("replica-1");

            // Still saturated, so the operator tries again: the knob is locked for cooldown-after-revert.
            ApplyResult second = replica.apply(RAISE, 4, 5, "load test: second attempt after the revert");
            // No third change: for longer than the normal cooldown (10 s) the pool stays at 4.
            await("pool stays at 4 after the revert").during(Duration.ofSeconds(25)).atMost(Duration.ofSeconds(40))
                    .pollInterval(Duration.ofMillis(500)).until(() -> replica.maximumPoolSize() == 4);
            ApplyResult third = replica.apply(RAISE, 4, 5, "load test: third attempt");
            long applied4to5 = replica.audit().stream()
                    .filter(e -> "apply".equals(e.path("action").asText()) && !"REFUSED".equals(e.path("outcome").asText()))
                    .count();

            scenario.record("load " + load.stats());
            scenario.record("outcome " + entry.path("outcome").asText() + " verdict " + entry.path("verdict").asText()
                    + " reasons " + entry.path("reasons") + " evidence " + entry.path("evidence"));
            scenario.record("second apply -> " + second.status() + " " + second.code() + "; third -> " + third.status()
                    + " " + third.code() + "; executed applies " + applied4to5);
            scenario.record("server sessions replica-1: max up to the revert " + sessionsAtRevert + ", max overall "
                    + probe.maxOf("replica-1") + " (" + probe.count() + " readings); pool max now "
                    + replica.maximumPoolSize() + ", open connections " + replica.dataSource().getHikariPoolMXBean()
                            .getTotalConnections());

            assertThat(entry.path("outcome").asText()).as(entry.toString()).isEqualTo("REVERTED");
            assertThat(entry.path("verdict").asText()).isEqualTo("REGRESSED");
            assertThat(entry.path("reasons").toString()).contains("R2").contains("slower than before");
            assertThat(replica.maximumPoolSize()).isEqualTo(4);
            assertThat(second.code()).isEqualTo("COOLDOWN");
            assertThat(second.status()).isEqualTo(409);
            assertThat(third.code()).isEqualTo("COOLDOWN");
            assertThat(applied4to5).as("changes executed").isEqualTo(1);
            assertThat(sessionsAtRevert).as("server-side sessions while raised").isLessThanOrEqualTo(5);
            assertThat(scenario.serverLog().count(ServerLog.TOO_MANY_CLIENTS)).isZero();
        }
    }
}
