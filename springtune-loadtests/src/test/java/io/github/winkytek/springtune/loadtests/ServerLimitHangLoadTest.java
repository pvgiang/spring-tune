package io.github.winkytek.springtune.loadtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.winkytek.springtune.loadtests.Replica.ApplyResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A server-limit read that hangs on a half-open TCP connection (iteration 6 brief row 9, review R3 of iteration 5).
 * The replica's pool reaches PostgreSQL through an in-JVM {@link TcpRelay}; the relay stops forwarding the one
 * connection that sends the limit query, so the query gets no answer and no error, as on a connection whose peer
 * vanished. pgjdbc's default {@code socketTimeout=0} would wait for the kernel (~15 min) with a pool connection
 * pinned; the starter bounds the read with {@code setNetworkTimeout} at the pool's {@code connectionTimeout}.
 */
@RequiresPostgres
class ServerLimitHangLoadTest {

    /** The pool's {@code connectionTimeout}, and so the bound of the server-limit read. */
    private static final long BOUND_MS = 3_000;
    /** Ceiling, not a measurement: the bound plus CPU contention on a shared machine. */
    private static final Duration SLACK = Duration.ofSeconds(5);
    /** A fragment of {@code ServerConnectionLimit.POSTGRES_QUERY}, sent only by the limit read. */
    private static final String LIMIT_QUERY = "current_setting('superuser_reserved_connections')";

    @Test
    void hungServerLimitReadIsRefusedWithinTheBoundReleasesItsConnectionAndTheNextRaiseSucceeds() throws Exception {
        try (Scenario scenario = new Scenario("ServerLimitHangLoadTest.hungRead")) {
            ActivityProbe probe = scenario.probe();
            PostgresBackend backend = scenario.backend();
            TcpRelay relay = scenario.closeLater(new TcpRelay(backend.host(), backend.port()));
            Map<String, String> settings = Replica.defaults(4, 20, 3);
            settings.put("spring.datasource.hikari.connection-timeout", String.valueOf(BOUND_MS));
            String url = "jdbc:postgresql://127.0.0.1:" + relay.port() + "/" + scenario.database()
                    + "?ApplicationName=replica-1";
            Replica replica = scenario.closeLater(Replica.start("replica-1", url, scenario.role("r1", -1),
                    Scenario.PASSWORD, settings));
            OpenLoopJdbcLoad load = scenario.load(OpenLoopJdbcLoad.on("replica-1", replica.dataSource()).rate(200)
                    .workers(12).queue(100).start(Workloads.sleep(0.03)));
            JsonNode rec = Operator.awaitRecommendation(replica, Operator.action("INCREASE"), "INCREASE 4 -> 5");

            // The network stops answering the limit read.
            relay.stopForwardingOn(LIMIT_QUERY);
            Instant start = Instant.now();
            ApplyResult hung = replica.apply(rec, "load test: raise while the limit read hangs");
            Duration answeredAfter = Duration.between(start, Instant.now());
            List<TcpRelay.Link> frozen = relay.frozen();
            scenario.record("apply during the hang -> " + hung.status() + " " + hung.code() + " after "
                    + answeredAfter.toMillis() + " ms: " + hung.body().path("message").asText());
            assertThat(frozen).as("the limit read's connection was frozen").hasSize(1);
            assertThat(hung.status()).isEqualTo(409);
            assertThat(hung.code()).isEqualTo("SERVER_LIMIT_UNKNOWN");
            assertThat(answeredAfter).isLessThan(Duration.ofMillis(BOUND_MS).plus(SLACK));
            assertThat(replica.maximumPoolSize()).isEqualTo(4);

            // The read itself ends within the bound: pgjdbc's socket read times out, HikariCP evicts the connection
            // (it closes the socket, which the relay sees) and refills the pool.
            TcpRelay.Link link = frozen.get(0);
            await("the pool gives the frozen connection up").atMost(Duration.ofMillis(BOUND_MS).plus(SLACK))
                    .pollInterval(Duration.ofMillis(50)).until(() -> link.clientClosedAt != null);
            Duration heldFor = Duration.between(link.frozenAt, link.clientClosedAt);
            await("the pool is back to 4 connections").atMost(SLACK.multipliedBy(2))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> replica.dataSource().getHikariPoolMXBean().getTotalConnections() == 4);
            String budget = replica.applyPlan().map(p -> p.path("budget").toString()).orElse("");
            scenario.record("frozen " + link + "; connection held " + heldFor.toMillis() + " ms after the query (bound "
                    + BOUND_MS + " ms); pool total " + replica.dataSource().getHikariPoolMXBean().getTotalConnections());
            scenario.record("plan budget after the hang " + budget);
            assertThat(budget).as("the read ended with an error, recorded for the plan")
                    .contains("re-reading it failed at").contains("SERVER_LIMIT_UNKNOWN");

            // The network is back: the next raise reads the limit again and is applied.
            relay.resume();
            JsonNode again = Operator.awaitRecommendation(replica, Operator.action("INCREASE"), "INCREASE 4 -> 5");
            ApplyResult raised = replica.apply(again, "load test: raise after the network is back");
            scenario.record("apply after resume -> " + raised.status() + " " + raised.code() + ": "
                    + raised.body().path("message").asText());
            scenario.record("load " + load.stats() + "; server sessions replica-1 max " + probe.maxOf("replica-1"));
            assertThat(raised.status()).as(raised.body().toString()).isEqualTo(200);
            assertThat(replica.maximumPoolSize()).isEqualTo(5);
            assertThat(relay.frozen()).hasSize(1);
        }
    }
}
