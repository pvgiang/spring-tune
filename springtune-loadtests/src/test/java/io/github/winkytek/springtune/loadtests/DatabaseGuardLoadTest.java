package io.github.winkytek.springtune.loadtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.winkytek.springtune.loadtests.Replica.ApplyResult;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * What only a real database can show: a configured budget larger than the server (brief row 7,
 * {@code BUDGET_EXCEEDS_SERVER}) and a database that refuses new connections while threads wait (row 8, bar 3:
 * "a failing-DB scenario in which apply is refused").
 */
@RequiresPostgres
class DatabaseGuardLoadTest {

    @Test
    void budgetLargerThanTheServerIsRefused() throws Exception {
        try (Scenario scenario = new Scenario("DatabaseGuardLoadTest.budgetExceedsServer")) {
            ActivityProbe probe = scenario.probe();
            // database-max-connections=100 on a server that accepts 20 from application roles: cap floor(80 / 3) = 26.
            Replica replica = scenario.replica("replica-1", scenario.role("r1", -1), Replica.defaults(4, 100, 3));
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(Workloads.sleep(0.03)));

            JsonNode rec = Operator.awaitRecommendation(replica, Operator.action("INCREASE"), "INCREASE 4 -> 5");
            JsonNode plan = replica.applyPlan().orElseThrow();
            ApplyResult applied = replica.apply(rec, "load test: budget configured for a bigger server");

            scenario.record("load " + load.stats());
            scenario.record("plan budget " + plan.path("budget"));
            scenario.record("apply -> " + applied.status() + " " + applied.code() + ": "
                    + applied.body().path("message").asText());
            scenario.record("server sessions replica-1 max " + probe.maxOf("replica-1"));

            assertThat(plan.path("budget").toString()).contains("= 20; database-max-connections 100 > 20")
                    .contains("BUDGET_EXCEEDS_SERVER");
            assertThat(applied.status()).isEqualTo(409);
            assertThat(applied.code()).isEqualTo("BUDGET_EXCEEDS_SERVER");
            assertThat(replica.maximumPoolSize()).isEqualTo(4);
            assertThat(probe.maxOf("replica-1")).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void databaseRefusingConnectionsIsNotAnswerWithALargerPool() throws Exception {
        try (Scenario scenario = new Scenario("DatabaseGuardLoadTest.failingDatabase")) {
            ActivityProbe probe = scenario.probe();
            String role = scenario.role("r1", -1);
            Map<String, String> settings = Replica.defaults(4, 20, 3);
            settings.put("spring.datasource.hikari.connection-timeout", "2000");
            Replica replica = scenario.replica("replica-1", role, settings);
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(Workloads.sleep(0.03)));
            await("pool filled").atMost(Duration.ofSeconds(30)).until(() -> probe.maxOf("replica-1") == 4);

            // The database starts refusing: the role may hold only 2 sessions, and the 4 it has are terminated, as
            // in a failover or an operator killing sessions. HikariCP re-opens 2; every further attempt is 53300.
            scenario.admin("ALTER ROLE " + role + " CONNECTION LIMIT 2");
            scenario.admin("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '" + role + "'");

            Set<String> seen = new TreeSet<>();
            JsonNode rec = Operator.awaitRecommendation(replica, r -> {
                seen.add(r.path("ruleId").asText() + " " + r.path("action").asText());
                return true;
            }, "a recommendation");
            ApplyResult reviewed = replica.apply(rec, "load test: apply what is recommended");
            ApplyResult raise = replica.apply(PostgresApplyLoadTest.RAISE, 4, 5, "load test: raise anyway");
            // For a whole window more, nothing recommends a raise.
            await("no INCREASE while the database refuses connections").during(Duration.ofSeconds(20))
                    .atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                    .until(() -> {
                        replica.recommendation().ifPresent(r -> seen.add(r.path("ruleId").asText() + " "
                                + r.path("action").asText()));
                        return seen.stream().noneMatch(s -> s.endsWith("INCREASE"));
                    });
            long executed = replica.audit().stream().filter(e -> "apply".equals(e.path("action").asText())
                    && !"REFUSED".equals(e.path("outcome").asText())).count();
            ServerLog log = scenario.serverLog();

            scenario.record("load " + load.stats());
            scenario.record("recommendation " + rec);
            scenario.record("recommendations seen " + seen);
            scenario.record("apply reviewed -> " + reviewed.status() + " " + reviewed.code() + "; apply "
                    + PostgresApplyLoadTest.RAISE + " -> " + raise.status() + " " + raise.code() + "; executed "
                    + executed);
            scenario.record("server log: role-limit refusals " + log.count(ServerLog.ROLE_LIMIT)
                    + ", too-many-clients " + log.count(ServerLog.TOO_MANY_CLIENTS) + "; open connections "
                    + replica.dataSource().getHikariPoolMXBean().getTotalConnections());

            assertThat(rec.path("ruleId").asText()).as(rec.toString()).isEqualTo("ST-REC-HIKARI-004");
            assertThat(rec.path("action").asText()).isEqualTo("HOLD");
            assertThat(seen).noneMatch(s -> s.endsWith("INCREASE"));
            assertThat(reviewed.code()).isEqualTo("RECOMMENDATION_IS_HOLD");
            assertThat(raise.code()).isIn("RECOMMENDATION_IS_HOLD", "NO_CURRENT_RECOMMENDATION");
            assertThat(executed).isZero();
            assertThat(replica.maximumPoolSize()).isEqualTo(4);
            assertThat(log.count(ServerLog.ROLE_LIMIT)).as("the database refused connections").isPositive();
        }
    }
}
