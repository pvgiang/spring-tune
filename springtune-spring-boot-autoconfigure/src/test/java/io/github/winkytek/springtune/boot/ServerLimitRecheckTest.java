package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The server-limit re-read of a real Hikari pool (H2, with the limit query replaced by a {@link HikariComponent.LimitReader}
 * stub, since H2 has no limit to read): re-read on every apply attempt, a lowered limit seen at once, a failed or
 * slow read reported as not checked (with the last known value and when it was read), bounded by
 * {@code connectionTimeout}, and the sampler never waiting for it.
 */
class ServerLimitRecheckTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final SpringTuneProperties properties = new SpringTuneProperties();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile Integer limit = 20;
    private volatile SQLException failure;
    private volatile CountDownLatch gate;
    private HikariDataSource dataSource;
    private HikariComponent component;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:recheck;DB_CLOSE_DELAY=-1");
        dataSource.setPoolName("orders");
        dataSource.setMaximumPoolSize(2);
        dataSource.setConnectionTimeout(250);
        try (Connection ignored = dataSource.getConnection()) {
            // starts the pool
        }
        properties.setMode(SpringTuneProperties.Mode.APPLY);
        properties.getBudget().setDatabaseMaxConnections(20);
        properties.getBudget().setMaxReplicas(3);
        component = new HikariComponent("dataSource", dataSource, TimerReader.NONE, properties, Optional.of(5), clock,
                this::read);
    }

    @AfterEach
    void tearDown() {
        CountDownLatch g = gate;
        if (g != null) {
            g.countDown();
        }
        dataSource.close();
    }

    private ServerConnectionLimit read(Connection connection) throws SQLException {
        reads.incrementAndGet();
        CountDownLatch g = gate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new ServerConnectionLimit(ServerConnectionLimit.Status.KNOWN, limit, "PostgreSQL",
                "PostgreSQL max_connections " + (limit + 3) + " - superuser_reserved_connections 3"
                        + " - reserved_connections 0 = " + limit);
    }

    private String planServerLine() {
        Recommendation raise = new Recommendation("ST-REC-HIKARI-001", "hikari.orders.maximumPoolSize",
                Action.INCREASE, 2, 3, "saturated", Map.of(), Confidence.HIGH, List.of(), clock.instant());
        List<String> budget = component.applyPlan(raise).orElseThrow().budget();
        return budget.get(budget.size() - 1);
    }

    @Test
    void everyApplyReReadsAndALoweredLimitIsSeenAtOnce() {
        ServerConnectionLimit first = component.writableKnob().serverLimit().get();
        assertThat(first.limit()).isEqualTo(20);
        assertThat(first.checkedAt()).isEqualTo(clock.instant());
        clock.advance(Duration.ofMinutes(5));
        limit = 10; // max_connections lowered (server restarted) while the application kept running
        ServerConnectionLimit second = component.writableKnob().serverLimit().get();
        assertThat(second.limit()).isEqualTo(10);
        assertThat(reads).hasValue(2);
        assertThat(component.serverLimit().limit()).isEqualTo(10);
        assertThat(planServerLine()).contains("database-max-connections 20 > 10")
                .contains("BUDGET_EXCEEDS_SERVER").contains("last checked at 2026-01-01T00:05:00Z");
    }

    @Test
    void failedReReadIsNotCheckedAndKeepsTheLastKnownValueForThePlan() {
        component.recheckServerLimit();
        clock.advance(Duration.ofMinutes(5));
        failure = new SQLException("Connection refused");
        ServerConnectionLimit fresh = component.recheckServerLimit();
        assertThat(fresh.status()).isEqualTo(ServerConnectionLimit.Status.NOT_READ);
        assertThat(fresh.detail()).contains("Connection refused")
                .contains("last known limit 20, read at 2026-01-01T00:00:00Z");
        // The cached value stays visible, marked: the plan shows when it was checked and that a raise is refused.
        ServerConnectionLimit cached = component.serverLimit();
        assertThat(cached.known()).isTrue();
        assertThat(cached.recheckFailure()).contains("re-reading it failed at 2026-01-01T00:05:00Z");
        assertThat(planServerLine()).contains("last checked at 2026-01-01T00:00:00Z")
                .contains("SERVER_LIMIT_UNKNOWN");
        component.sample(clock.instant());
        assertThat(component.signals().get("serverConnectionLimit").toString())
                .contains("last checked at 2026-01-01T00:00:00Z");

        failure = null;
        assertThat(component.recheckServerLimit().known()).isTrue();
        assertThat(component.serverLimit().recheckFailure()).isNull();
    }

    @Test
    void neverReadSuccessfullySaysSo() {
        failure = new SQLException("password authentication failed");
        ServerConnectionLimit fresh = component.recheckServerLimit();
        assertThat(fresh.status()).isEqualTo(ServerConnectionLimit.Status.NOT_READ);
        assertThat(fresh.detail()).contains("never read successfully");
        assertThat(planServerLine()).startsWith("server limit not checked yet").contains("SERVER_LIMIT_UNKNOWN");
    }

    @Test
    void slowReadIsBoundedByConnectionTimeoutAndNeverBlocksTheSampler() throws Exception {
        component.recheckServerLimit();
        gate = new CountDownLatch(1); // the server stops answering
        long start = System.nanoTime();
        ServerConnectionLimit fresh = component.recheckServerLimit();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(fresh.status()).isEqualTo(ServerConnectionLimit.Status.NOT_READ);
        assertThat(fresh.detail()).contains("no answer within connectionTimeout (250 ms)")
                .contains("last known limit 20");
        assertThat(waitedMs).as("waited for the re-read").isBetween(200L, 5_000L);

        // The sampler does not wait for the read still hanging in the background.
        long sampleStart = System.nanoTime();
        component.sample(clock.instant());
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sampleStart)).isLessThan(200L);

        // A second apply joins the hanging read instead of borrowing another connection.
        component.recheckServerLimit();
        assertThat(reads).hasValue(2);
        gate.countDown();
    }

    @Test
    void unsupportedDatabaseIsNotReReadAndWithoutBudgetNothingIsRead() throws Exception {
        HikariComponent h2 = new HikariComponent("dataSource", dataSource, TimerReader.NONE, properties,
                Optional.of(5), clock, ServerConnectionLimit::read);
        assertThat(h2.recheckServerLimit().status()).isEqualTo(ServerConnectionLimit.Status.UNSUPPORTED);
        assertThat(h2.recheckServerLimit().status()).isEqualTo(ServerConnectionLimit.Status.UNSUPPORTED);
        HikariComponent noBudget = new HikariComponent("dataSource", dataSource, TimerReader.NONE, properties,
                Optional.empty(), clock, this::read);
        assertThat(noBudget.recheckServerLimit().status()).isEqualTo(ServerConnectionLimit.Status.NOT_APPLICABLE);
        assertThat(reads).hasValue(0);
    }

    @Test
    void capacityIsTheTotalConnectionCount() {
        WritableKnob.Capacity capacity = component.writableKnob().capacity().get();
        assertThat(capacity.held()).isEqualTo(dataSource.getHikariPoolMXBean().getTotalConnections());
        assertThat(capacity.demand()).isFalse();
    }
}
