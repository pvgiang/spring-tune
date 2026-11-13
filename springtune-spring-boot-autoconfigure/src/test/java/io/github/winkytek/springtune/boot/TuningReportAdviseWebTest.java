package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code GET /actuator/tuning/report} in advise mode with Spring Security, over a snapshot whose every free-text
 * field is hostile: pool and bean names, knob ids and sources, signals, recommendation reason, evidence keys and
 * values, related rule ids, evaluation fields, and an apply block with {@code who}, {@code why}, exception texts in
 * reasons, evidence and capacity line, a forged rule id and action. Serialized by the actuator's JSON mapper.
 */
class TuningReportAdviseWebTest {

    static final String POOL = TuningReportApplyWebTest.POOL;
    static final String EXECUTOR = TuningReportApplyWebTest.EXECUTOR;
    static final String WHO = TuningReportApplyWebTest.WHO;
    static final String WHY = TuningReportApplyWebTest.WHY;
    static final String EXCEPTION = TuningReportApplyWebTest.EXCEPTION;
    static final Instant AT = Instant.parse("2026-11-09T10:00:00Z");

    static TuningSnapshot hostileSnapshot() {
        String hikari = "hikari:" + POOL;
        String executor = "executor:" + EXECUTOR;
        String knob = "hikari." + POOL + ".maximumPoolSize";
        List<Knob> hikariKnobs = List.of(
                new Knob(knob, hikari, "maximumPoolSize", 10, 10L, 1L, 13L, "count", EXCEPTION),
                new Knob("hikari." + POOL + ".minimumIdle", hikari, "minimumIdle", 10, null, 0L, null, "count", "x"),
                new Knob("hikari." + POOL + ".secret", hikari, POOL, 7, null, null, null, "count", EXCEPTION));
        List<Knob> executorKnobs = List.of(
                new Knob("executor." + EXECUTOR + ".maxPoolSize", executor, "maxPoolSize", 4, null, 1L, null, "count",
                        "ThreadPoolTaskExecutor"));
        Map<String, Object> signals = Map.of("serverConnectionLimit", EXCEPTION, "who", WHO);
        List<TuningSnapshot.Component> components = List.of(
                new TuningSnapshot.Component(hikari, 31, hikariKnobs, signals),
                new TuningSnapshot.Component(executor, 31, executorKnobs, signals),
                new TuningSnapshot.Component("redis:" + POOL, 3, List.of(), signals));

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("window", "5m");
        evidence.put("samples", "31");
        evidence.put("samplesWithWaitingThreads", "100%");
        evidence.put("meanConnectionHoldTime", "40 ms");
        evidence.put("peakActiveConnections", "3 of 10");
        evidence.put("budgetCap", "13");
        evidence.put(POOL + ".waiting", "12");                // key carries the pool name
        evidence.put("maxWaitingThreads", EXCEPTION);          // allowlisted key, free-text value
        evidence.put("queueCapacity", "10.1.2.3");             // allowlisted key, IPv4 value
        evidence.put("connectionHoldTime", "unavailable");     // non-numeric
        Recommendation raise = new Recommendation("ST-REC-HIKARI-001", knob, Action.INCREASE, 10, 13,
                "Raise " + POOL + " (" + EXCEPTION + ")", evidence, Confidence.HIGH, List.of(POOL), AT);
        Recommendation forged = new Recommendation("ST-REC-" + POOL, knob, Action.HOLD, 10, 10, WHY, Map.of(),
                Confidence.LOW, List.of(), AT);

        List<RuleEvaluation> evaluations = List.of(
                new RuleEvaluation(hikari, "ST-REC-HIKARI-001", RuleEvaluation.Status.RECOMMENDING, null, null, null,
                        null, null, null),
                new RuleEvaluation(hikari, "ST-REC-HIKARI-002", RuleEvaluation.Status.QUIET, null, null, null, null,
                        null, "ST-REC-HIKARI-001"),
                new RuleEvaluation(hikari, "ST-REC-HIKARI-003", RuleEvaluation.Status.COLLECTING, 31, 3, 310L, 86390L,
                        null, null),
                new RuleEvaluation(executor, "ST-REC-EXEC-001", RuleEvaluation.Status.NO_SIGNAL, null, null, null,
                        null, List.of("hikaricp.connections.usage", POOL + ".usage"), WHO),
                new RuleEvaluation("redis:" + POOL, "ST-REC-REDIS-001", RuleEvaluation.Status.QUIET, null, null, null,
                        null, null, null));

        Map<String, Long> before = new LinkedHashMap<>();
        before.put("maximumPoolSize", 10L);
        before.put("minimumIdle", 10L);
        before.put(POOL, 5432L);
        Map<String, Long> after = Map.of("maximumPoolSize", 13L, "minimumIdle", 13L);
        List<ChangeAuditEntry> audit = List.of(
                new ChangeAuditEntry("change-2", AT, "apply", WHO, WHY, "ST-REC-HIKARI-001", knob, before, after,
                        ChangeAuditEntry.Outcome.CONFIRMED, "OK", null, List.of(EXCEPTION),
                        Map.of("timeoutsPerAttempt", EXCEPTION), AT.plusSeconds(330), false, EXCEPTION),
                new ChangeAuditEntry("change-1 " + WHO, AT, WHY, WHO, EXCEPTION, "ST-REC-" + POOL,
                        "hikari.unknown." + POOL, Map.of(), Map.of(), ChangeAuditEntry.Outcome.REFUSED, EXCEPTION,
                        "NO_CURRENT_RECOMMENDATION", List.of(EXCEPTION), Map.of(), AT));
        TuningSnapshot.Apply apply = new TuningSnapshot.Apply(true, true, EXCEPTION, WHO, Map.of("who", WHO),
                List.of(), audit);
        return new TuningSnapshot("ADVISE", false, AT, new TuningSnapshot.Budget(100, 6, 0.8, 13), components,
                List.of(raise, forged), evaluations, List.of(), apply);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(User.withUsername("ops").password("{noop}pw").roles("OPS").build());
        }

        /** Replaces the auto-configured service: serves the hostile snapshot. */
        @Bean
        TuningService hostileService(SpringTuneProperties properties, ListableBeanFactory beanFactory) {
            return new TuningService(properties, beanFactory, Clock.systemUTC()) {
                @Override
                public TuningSnapshot snapshot() {
                    return hostileSnapshot();
                }
            };
        }
    }

    private static ConfigurableApplicationContext context;
    private static String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(App.class).properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "management.endpoints.web.exposure.include=tuning").run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/actuator/tuning";
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (authenticated) {
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("ops:pw".getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reportOfAHostileSnapshotKeepsOnlyAllowlistedValues() throws Exception {
        HttpResponse<String> snapshot = get("", true);
        assertThat(snapshot.statusCode()).isEqualTo(200);
        assertThat(snapshot.body()).as("the trap is armed").contains(POOL).contains(WHO).contains(WHY)
                .contains("db.prod.acme.internal");

        HttpResponse<String> report = get("/report", true);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        String body = report.body();
        ReportChecks.assertNoHostileValue(body);
        assertThat(ReportChecks.assertSchema(body)).isGreaterThan(30);
        Files.writeString(Path.of("target", "report-example-advise.json"), body);

        // What is kept: pseudonyms, allowlisted settings, numeric evidence, enums, numbers.
        assertThat(body).contains("\"mode\":\"ADVISE\"").contains("\"ref\":\"hikari#1\"")
                .contains("\"ref\":\"executor#1\"").doesNotContain("redis")
                .contains("\"knob\":\"hikari#1.maximumPoolSize\"").contains("\"samplesWithWaitingThreads\":\"100%\"")
                .contains("\"peakActiveConnections\":\"3 of 10\"").contains("\"meanConnectionHoldTime\":\"40 ms\"")
                .doesNotContain("maxWaitingThreads").doesNotContain("connectionHoldTime\"")
                .contains("\"missingMeters\":[\"hikaricp.connections.usage\"]")
                .contains("\"supersededBy\":\"ST-REC-HIKARI-001\"").contains("\"ref\":\"change-2\"")
                .contains("\"capacityObtained\":false").contains("\"code\":\"NO_CURRENT_RECOMMENDATION\"")
                .contains("\"databaseMaxConnections\":100").contains("\"perInstanceCap\":13")
                .contains("feedbackUrl\":\"https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation"
                        + "-feedback.yml&title=");
        Map<?, ?> root = (Map<?, ?>) ReportChecks.parse(body);
        assertThat((List<?>) root.get("recommendations")).as("forged rule id dropped").hasSize(1);
        assertThat((List<?>) root.get("verdicts")).hasSize(2);
        assertThat((List<?>) root.get("evaluations")).hasSize(4);

        assertThat(get("/report", false).statusCode()).isEqualTo(401);
        assertThat(get("/nope", true).statusCode()).isEqualTo(404);
    }
}
