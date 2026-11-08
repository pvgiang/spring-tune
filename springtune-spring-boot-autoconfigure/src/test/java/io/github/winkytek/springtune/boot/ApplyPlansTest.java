package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Read-only apply plans: property lines only where the prefix is certain, budget arithmetic, side effects. */
class ApplyPlansTest {

    private static Recommendation rec(String ruleId, String knobId, Action action, long from, long to) {
        return new Recommendation(ruleId, knobId, action, from, to, "r", Map.of(), Confidence.HIGH, List.of(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static SpringTuneProperties.Budget budget(Integer max, Integer replicas) {
        SpringTuneProperties.Budget budget = new SpringTuneProperties().getBudget();
        budget.setDatabaseMaxConnections(max);
        budget.setMaxReplicas(replicas);
        return budget;
    }

    @Test
    void primaryDataSourceIncreaseWithFixedPoolAndBudget() {
        ApplyPlan plan = ApplyPlans.hikari(rec("ST-REC-HIKARI-001", "hikari.orders.maximumPoolSize", Action.INCREASE,
                10, 13), "dataSource", "orders", 10, budget(100, 6), Optional.of(13)).orElseThrow();
        assertThat(plan.commit()).containsExactly("spring.datasource.hikari.maximum-pool-size=13",
                "spring.datasource.hikari.minimum-idle=13");
        assertThat(plan.budget()).containsExactly(
                "per-instance cap = floor(database-max-connections x safety-factor / max-replicas) = floor(100 x 0.8 / 6) = 13",
                "13 <= cap 13: within budget",
                "fleet at max replicas: 13 x 6 = 78 of 80 usable connections (this service only)");
        assertThat(plan.sideEffects()).anyMatch(s -> s.startsWith("minimumIdle equals maximumPoolSize"));
        assertThat(plan.persistence()).startsWith("ephemeral");
    }

    @Test
    void otherDataSourceNamesTheBeanInsteadOfGuessingAPrefix() {
        ApplyPlan plan = ApplyPlans.hikari(rec("ST-REC-HIKARI-001", "hikari.reports.maximumPoolSize", Action.INCREASE,
                4, 5), "reportsDataSource", "reports", 2, budget(null, null), Optional.empty()).orElseThrow();
        assertThat(plan.commit()).singleElement().asString().startsWith("maximumPoolSize=5 on DataSource bean"
                + " 'reportsDataSource' (pool 'reports')").contains("cannot know its property prefix")
                .doesNotContain("spring.datasource");
        assertThat(plan.budget()).singleElement().asString().contains("apply mode refuses to raise");
        assertThat(plan.sideEffects()).noneMatch(s -> s.startsWith("minimumIdle"));
    }

    @Test
    void decreaseIsLazyAndFreesConnections() {
        ApplyPlan plan = ApplyPlans.hikari(rec("ST-REC-HIKARI-003", "hikari.orders.maximumPoolSize", Action.DECREASE,
                20, 4), "dataSource", "orders", 5, budget(100, 6), Optional.of(13)).orElseThrow();
        assertThat(plan.commit()).contains("spring.datasource.hikari.minimum-idle=4");
        assertThat(plan.budget()).containsExactly("frees 16 connections per instance, 96 at 6 replicas");
        assertThat(plan.sideEffects()).anyMatch(s -> s.startsWith("minimumIdle (5) is above the new maximum"))
                .anyMatch(s -> s.startsWith("Hikari shrinks lazily"));
    }

    @Test
    void holdHasNoPlan() {
        assertThat(ApplyPlans.hikari(rec("ST-REC-HIKARI-002", "hikari.orders.maximumPoolSize", Action.HOLD, 10, 10),
                "dataSource", "orders", 10, budget(null, null), Optional.empty())).isEmpty();
    }

    @Test
    void applicationTaskExecutorUsesBootProperties() {
        ApplyPlan core = ApplyPlans.executor(rec("ST-REC-EXEC-001", "executor.applicationTaskExecutor.corePoolSize",
                Action.INCREASE, 8, 10), "applicationTaskExecutor", "corePoolSize", 8, 8, true).orElseThrow();
        assertThat(core.commit()).containsExactly("spring.task.execution.pool.core-size=10",
                "spring.task.execution.pool.max-size=10");
        assertThat(core.sideEffects()).anyMatch(s -> s.contains("maxPoolSize moves to 10 first"))
                .anyMatch(s -> s.contains("queue is unbounded"));

        ApplyPlan other = ApplyPlans.executor(rec("ST-REC-EXEC-001", "executor.mailExecutor.maxPoolSize",
                Action.INCREASE, 16, 20), "mailExecutor", "maxPoolSize", 4, 16, false).orElseThrow();
        assertThat(other.commit()).singleElement().asString().startsWith("maxPoolSize=20 on ThreadPoolTaskExecutor"
                + " bean 'mailExecutor'").doesNotContain("spring.task");
    }

    @Test
    void increasePlanComparesTheBudgetWithTheServersLimit() {
        Recommendation raise = rec("ST-REC-HIKARI-001", "hikari.orders.maximumPoolSize", Action.INCREASE, 10, 13);
        ServerConnectionLimit server = new ServerConnectionLimit(ServerConnectionLimit.Status.KNOWN, 20, "PostgreSQL",
                "PostgreSQL max_connections 23 - superuser_reserved_connections 3 - reserved_connections 0 = 20");
        assertThat(ApplyPlans.hikari(raise, "dataSource", "orders", 10, budget(100, 6), Optional.of(13), server)
                .orElseThrow().budget()).last().asString().isEqualTo("server limit: PostgreSQL max_connections 23"
                        + " - superuser_reserved_connections 3 - reserved_connections 0 = 20; database-max-connections"
                        + " 100 > 20: apply mode refuses to raise the pool (BUDGET_EXCEEDS_SERVER)");
        assertThat(ApplyPlans.hikari(raise, "dataSource", "orders", 10, budget(20, 1), Optional.of(16), server)
                .orElseThrow().budget()).last().asString().endsWith("database-max-connections 20 <= 20: consistent"
                        + " with the server");
        ServerConnectionLimit h2 = new ServerConnectionLimit(ServerConnectionLimit.Status.UNSUPPORTED, null, "H2",
                "SpringTune cannot read the connection limit of H2; database-max-connections is not checked against"
                        + " the server");
        assertThat(ApplyPlans.hikari(raise, "dataSource", "orders", 10, budget(100, 6), Optional.of(13), h2)
                .orElseThrow().budget()).last().asString().startsWith("server limit not checked: SpringTune cannot"
                        + " read the connection limit of H2");
    }

    @Test
    void serverLimitOfAnUnknownDatabaseIsUnsupported() throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:limit")) {
            ServerConnectionLimit limit = ServerConnectionLimit.read(connection);
            assertThat(limit.status()).isEqualTo(ServerConnectionLimit.Status.UNSUPPORTED);
            assertThat(limit.limit()).isNull();
            assertThat(limit.product()).isEqualTo("H2");
        }
    }
}
