package io.github.winkytek.springtune.loadtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import io.github.winkytek.springtune.loadtests.Replica.ApplyResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bar 2: "a load test in which 3 replicas raising at once never exceed the budget", and the assumptions behind it
 * attacked (brief rows 5 and 6). The server enforces its limit itself: application roles may open
 * {@value PostgresBackend#APP_CONNECTION_LIMIT} sessions, and an overshoot is SQLSTATE 53300 in the client and a
 * {@code too many clients} / reserved-slots line in the server log, which sampling cannot miss.
 *
 * <p>Budget everywhere: {@code database-max-connections=20}, {@code safety-factor=0.8} so 16 usable, and
 * {@code max-replicas=3} so a per-instance cap of {@code floor(16 / 3) = 5}. Every replica starts at 4/4 and is
 * saturated, so each one is recommended 4 &rarr; 5.
 */
@RequiresPostgres
class BudgetLoadTest {

    static final int DB_MAX = 20;
    static final int USABLE = 16;
    static final int CAP = 5;
    static final Set<String> NOTHING_TO_APPLY = Set.of("RECOMMENDATION_IS_HOLD", "ABOVE_BUDGET_CAP",
            "NO_CURRENT_RECOMMENDATION");

    @Test
    void threeReplicasRaisingAtOnceStayWithinTheBudget() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.threeReplicasAtOnce")) {
            ActivityProbe probe = scenario.probe();
            Fleet fleet = Fleet.start(scenario, 3, Replica.defaults(4, DB_MAX, 3), i -> Workloads.sleep(0.03));
            raiseAtOnceAndCheckTheBudget(scenario, probe, fleet, 3);
        }
    }

    @Test
    void raiseWhileHikariReplacesConnections() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.shortMaxLifetime")) {
            ActivityProbe probe = scenario.probe();
            Map<String, String> settings = Replica.defaults(4, DB_MAX, 3);
            // HikariCP's minimum: every connection is retired and replaced about 30 s after it was opened, i.e.
            // during the verification window of a raise made after the 15 s saturation window.
            settings.put("spring.datasource.hikari.max-lifetime", "30000");
            Fleet fleet = Fleet.start(scenario, 3, settings, i -> Workloads.sleep(0.03));
            raiseAtOnceAndCheckTheBudget(scenario, probe, fleet, 3);
            ServerLog log = scenario.serverLog();
            long authorized = fleet.roles.stream().mapToLong(log::authorizedConnections).sum();
            scenario.record("authorized connections of the replica roles " + authorized + " (at most "
                    + 3 * CAP + " open at once)");
            assertThat(authorized).as("Hikari replaced connections during the run").isGreaterThan(3L * CAP);
        }
    }

    @Test
    void oneReplicaRevertsWhileTwoRaise() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.oneRevertsTwoRaise")) {
            ActivityProbe probe = scenario.probe();
            // replica-3's database work gets slower with a 5th session (server-side), so its raise regresses.
            Fleet fleet = Fleet.start(scenario, 3, Replica.defaults(4, DB_MAX, 3),
                    i -> i == 3 ? Workloads.slowerAbove(4, 0.03, 0.06) : Workloads.sleep(0.03));
            List<JsonNode> entries = raiseAtOnceAndCheckTheBudget(scenario, probe, fleet, 2);
            assertThat(outcome(entries.get(0))).isEqualTo("CONFIRMED");
            assertThat(outcome(entries.get(1))).isEqualTo("CONFIRMED");
            assertThat(outcome(entries.get(2))).isEqualTo("REVERTED");
            assertThat(fleet.replicas.get(2).maximumPoolSize()).isEqualTo(4);
        }
    }

    @Test
    void anotherServiceHoldsConnectionsDuringTheRaise() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.otherService")) {
            ActivityProbe probe = scenario.probe();
            // Another service on the same server holds K = 3 connections the budget knows nothing about. The
            // safety factor leaves 20 - 16 = 4 for everything else: these 3 plus the probe's session.
            String otherRole = scenario.role("other", -1);
            HikariDataSource other = scenario.closeLater(new HikariDataSource());
            other.setJdbcUrl(scenario.backend().jdbcUrl(scenario.database(), "other-service"));
            other.setUsername(otherRole);
            other.setPassword(Scenario.PASSWORD);
            other.setPoolName("other");
            other.setMaximumPoolSize(3);
            other.setMinimumIdle(3);
            scenario.load(OpenLoopJdbcLoad.on("other-service", other).rate(50).workers(3).queue(20)
                    .start(Workloads.sleep(0.03)));
            Fleet fleet = Fleet.start(scenario, 3, Replica.defaults(4, DB_MAX, 3), i -> Workloads.sleep(0.03));
            raiseAtOnceAndCheckTheBudget(scenario, probe, fleet, 3);
            scenario.record("other-service sessions max " + probe.maxOf("other-service"));
            assertThat(probe.maxOf("other-service")).isEqualTo(3);
        }
    }

    @Test
    void surgeReplicaNotCountedInMaxReplicasBreachesTheBudget() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.surgeNotCounted")) {
            ActivityProbe probe = scenario.probe();
            // A rolling update's surge pod: 4 replicas run, but max-replicas says 3, so each one's cap is still 5.
            Fleet fleet = Fleet.start(scenario, 4, Replica.defaults(4, DB_MAX, 3), i -> Workloads.sleep(0.03));
            List<ApplyResult> results = fleet.applyAtOnce(Operator.action("INCREASE"), "INCREASE 4 -> 5");
            List<JsonNode> entries = fleet.awaitFinal(results);
            probe.awaitReadings(3);
            ServerLog log = scenario.serverLog();
            record(scenario, probe, fleet, results, entries, log);

            // The negative control: this configuration is wrong, and the probe must see it.
            assertThat(results).allMatch(r -> r.status() == 200);
            assertThat(probe.maxSum(Fleet.SERVICE)).as("service sessions above the usable budget of " + USABLE)
                    .isGreaterThan(USABLE);
            assertThat(probe.samplesAbove(Fleet.SERVICE, USABLE)).isPositive();
        }
    }

    @Test
    void surgeReplicaCountedInMaxReplicasStaysWithinTheBudget() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.surgeCounted")) {
            ActivityProbe probe = scenario.probe();
            // Same 4 replicas, max-replicas=4: cap floor(16 / 4) = 4, so nobody may raise.
            Fleet fleet = Fleet.start(scenario, 4, Replica.defaults(4, DB_MAX, 4), i -> Workloads.sleep(0.03));
            List<ApplyResult> results = fleet.applyAtOnce(rec -> true, "a recommendation");
            List<JsonNode> entries = fleet.awaitFinal(results);
            probe.awaitReadings(3);
            ServerLog log = scenario.serverLog();
            record(scenario, probe, fleet, results, entries, log);

            assertThat(results).allMatch(r -> r.status() != 200 && NOTHING_TO_APPLY.contains(r.code()));
            assertThat(fleet.replicas).allMatch(r -> r.maximumPoolSize() == 4);
            assertThat(probe.maxSum(Fleet.SERVICE)).isLessThanOrEqualTo(USABLE);
            assertThat(log.count(ServerLog.TOO_MANY_CLIENTS)).isZero();
            assertThat(fleet.clientSqlState("53300")).isZero();
        }
    }

    /**
     * The other negative control: the server-log scan and the client SQLSTATE count must see a refused connection.
     * One role opens connections until the server refuses one.
     */
    @Test
    void serverLogAndClientSeeTheServersLimit() throws Exception {
        try (Scenario scenario = new Scenario("BudgetLoadTest.logSeesLimit")) {
            String role = scenario.role("flood", -1);
            List<Connection> open = new ArrayList<>();
            String refused = null;
            try {
                for (int i = 0; i < PostgresBackend.MAX_CONNECTIONS && refused == null; i++) {
                    try {
                        open.add(java.sql.DriverManager.getConnection(
                                scenario.backend().jdbcUrl(scenario.database(), "flood"), role, Scenario.PASSWORD));
                    } catch (SQLException ex) {
                        refused = ex.getSQLState();
                    }
                }
            } finally {
                for (Connection connection : open) {
                    connection.close();
                }
            }
            ServerLog log = scenario.serverLog();
            scenario.record("opened " + open.size() + " connections before the server refused one with SQLSTATE "
                    + refused + "; server-log lines " + log.count(ServerLog.TOO_MANY_CLIENTS));
            assertThat(refused).isEqualTo("53300");
            assertThat(open.size()).isLessThanOrEqualTo(PostgresBackend.APP_CONNECTION_LIMIT);
            assertThat(log.count(ServerLog.TOO_MANY_CLIENTS)).isPositive();
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static List<JsonNode> raiseAtOnceAndCheckTheBudget(Scenario scenario, ActivityProbe probe, Fleet fleet,
            int expectConfirmedAtLeast) throws Exception {
        List<ApplyResult> results = fleet.applyAtOnce(Operator.action("INCREASE"), "INCREASE 4 -> 5");
        List<JsonNode> entries = fleet.awaitFinal(results);
        // Once the cooldown is over, nothing more may be applied: every pool is at its cap of 5 (or reverted).
        fleet.awaitCooldownOver();
        List<ApplyResult> further = new ArrayList<>();
        for (Replica replica : fleet.replicas) {
            further.add(replica.apply(PostgresApplyLoadTest.RAISE, replica.maximumPoolSize(),
                    replica.maximumPoolSize() + 1, "load test: raise again"));
        }
        probe.awaitReadings(3);
        ServerLog log = scenario.serverLog();
        record(scenario, probe, fleet, results, entries, log);
        scenario.record("further applies: " + further.stream().map(r -> r.status() + " " + r.code()).toList());

        assertThat(results).as("all applies accepted").allMatch(r -> r.status() == 200);
        assertThat(entries.stream().filter(Objects::nonNull).filter(e -> "CONFIRMED".equals(outcome(e))).count())
                .isGreaterThanOrEqualTo(expectConfirmedAtLeast);
        for (Replica replica : fleet.replicas) {
            assertThat(probe.maxOf(replica.name())).as(replica.name() + " server-side sessions").isLessThanOrEqualTo(CAP);
        }
        assertThat(probe.maxSum(Fleet.SERVICE)).as("service sessions").isLessThanOrEqualTo(USABLE);
        assertThat(probe.maxTotal()).as("all client backends").isLessThanOrEqualTo(PostgresBackend.APP_CONNECTION_LIMIT);
        assertThat(log.count(ServerLog.TOO_MANY_CLIENTS)).as("too many clients in the server log").isZero();
        assertThat(fleet.clientSqlState("53300")).as("SQLSTATE 53300 in clients").isZero();
        for (int i = 0; i < further.size(); i++) {
            ApplyResult r = further.get(i);
            boolean reverted = "REVERTED".equals(outcome(entries.get(i)));
            assertThat(r.status()).as(fleet.replicas.get(i).name() + " further apply").isNotEqualTo(200);
            assertThat(r.code()).as(fleet.replicas.get(i).name() + " further apply")
                    .isIn(reverted ? Set.of("COOLDOWN") : NOTHING_TO_APPLY);
        }
        return entries;
    }

    private static void record(Scenario scenario, ActivityProbe probe, Fleet fleet, List<ApplyResult> results,
            List<JsonNode> entries, ServerLog log) {
        scenario.record("loads " + fleet.loadStats());
        for (int i = 0; i < fleet.replicas.size(); i++) {
            JsonNode entry = entries.get(i);
            scenario.record(fleet.replicas.get(i).name() + ": apply " + results.get(i).status() + " "
                    + results.get(i).code() + (entry == null ? "" : " -> " + outcome(entry) + " "
                            + entry.path("verdict").asText() + " " + entry.path("reasons")) + "; pool max now "
                    + fleet.replicas.get(i).maximumPoolSize());
        }
        scenario.record("server sessions max per application " + probe.maxByApplication() + "; service max "
                + probe.maxSum(Fleet.SERVICE) + " (" + probe.samplesAbove(Fleet.SERVICE, USABLE) + " of "
                + probe.count() + " readings above " + USABLE + "); all client backends max " + probe.maxTotal());
        scenario.record("server log: too-many-clients/reserved-slot lines " + log.count(ServerLog.TOO_MANY_CLIENTS)
                + "; client SQLSTATE 53300 " + fleet.clientSqlState("53300"));
    }

    private static String outcome(JsonNode entry) {
        return entry == null ? null : entry.path("outcome").asText();
    }
}
