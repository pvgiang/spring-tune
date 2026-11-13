package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code GET /actuator/tuning/report} in a real apply-mode web app with Spring Security and hostile names: a pool
 * named {@code orders-db.prod.internal}, an executor bean named like a JDBC URL, a principal that is an e-mail
 * address, a reason naming a ticket and an exception text as another reason. The full snapshot contains them (the
 * trap is armed); the report, serialized by the actuator's own JSON mapper, contains none of them.
 */
class TuningReportApplyWebTest {

    static final String POOL = "orders-db.prod.internal";
    static final String EXECUTOR = "jdbc:postgresql://10.1.2.3:5432/orders";
    static final String WHO = "alice@corp.example";
    static final String WHY = "ACME-123 deploy";
    static final String EXCEPTION = "Connection to db.prod.acme.internal:5432 refused. password authentication failed"
            + " for user \"orders_svc\"";

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
            return new InMemoryUserDetailsManager(
                    User.withUsername(WHO).password("{noop}pw").roles("TUNER").build());
        }

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:report-apply;DB_CLOSE_DELAY=-1");
            dataSource.setPoolName(POOL);
            dataSource.setMaximumPoolSize(4);
            return dataSource;
        }

        @Bean(name = EXECUTOR)
        ThreadPoolTaskExecutor hostileExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setQueueCapacity(10);
            return executor;
        }
    }

    private static ConfigurableApplicationContext context;
    private static String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        context = new SpringApplicationBuilder(App.class).properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "management.endpoints.web.exposure.include=tuning",
                "springtune.mode=apply",
                "springtune.budget.database-max-connections=100",
                "springtune.budget.max-replicas=4",
                "springtune.apply.required-role=TUNER").run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/actuator/tuning";
        context.getBean(HikariDataSource.class).getConnection().close();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    private HttpResponse<String> send(String method, String path, boolean authenticated, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString((WHO + ":pw").getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void reportIsServedNextToTheWriteOperationAndCarriesNoHostileValue() throws Exception {
        // Two audited requests with hostile free text: a refused apply naming the real knob, then a freeze.
        HttpResponse<String> apply = send("POST", "", true, "{\"action\":\"apply\",\"ruleId\":\"ST-REC-HIKARI-001\","
                + "\"knobId\":" + quote("hikari." + POOL + ".maximumPoolSize") + ",\"expectedCurrent\":4,\"reason\":"
                + quote(EXCEPTION) + "}");
        assertThat(apply.statusCode()).isIn(400, 409);
        HttpResponse<String> freeze = send("POST", "", true, "{\"action\":\"freeze\",\"reason\":" + quote(WHY) + "}");
        assertThat(freeze.statusCode()).as(freeze.body()).isEqualTo(200);

        TuningService service = context.getBean(TuningService.class);
        for (int i = 0; i < 3; i++) {
            service.sample();
        }

        HttpResponse<String> snapshot = send("GET", "", true, null);
        assertThat(snapshot.statusCode()).isEqualTo(200);
        assertThat(snapshot.body()).as("the trap is armed: the full snapshot names them").contains(POOL)
                .contains("10.1.2.3").contains(WHO).contains(WHY).contains("db.prod.acme.internal")
                .contains("\"evaluations\"").contains("\"status\":\"COLLECTING\"");

        HttpResponse<String> report = send("GET", "/report", true, null);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        assertThat(report.body()).contains("\"format\":\"springtune-report/1\"").contains("\"mode\":\"APPLY\"")
                .contains("\"ref\":\"hikari#1\"").contains("\"ref\":\"executor#1\"")
                .contains("\"knob\":\"hikari#1.maximumPoolSize\"").contains("\"outcome\":\"FROZEN\"")
                .contains("\"status\":\"COLLECTING\"");
        ReportChecks.assertNoHostileValue(report.body());
        assertThat(ReportChecks.assertSchema(report.body())).isGreaterThan(20);
        Files.writeString(Path.of("target", "report-example-apply.json"), report.body());

        assertThat(send("GET", "/report", false, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/other", true, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/" + POOL, true, null).statusCode()).isEqualTo(404);
    }

    /**
     * A rule id is request text: an authenticated caller (no required role needed, the refusal is audited) can post
     * any string matching {@code ST-REC-[A-Z]+-\\d{3}}, e.g. one that embeds the pool name in upper case. Only the
     * rule ids SpringTune itself defines may reach the report.
     */
    @Test
    void ruleIdFromARequestBodyIsNotCopiedUnlessItIsAKnownRule() throws Exception {
        HttpResponse<String> forged = send("POST", "", true, "{\"action\":\"apply\",\"ruleId\":\"ST-REC-ORDERSDB-001\","
                + "\"knobId\":\"x\",\"expectedCurrent\":4,\"reason\":\"r\"}");
        assertThat(forged.statusCode()).as(forged.body()).isIn(400, 409);
        assertThat(send("GET", "", true, null).body()).as("the trap is armed").contains("ST-REC-ORDERSDB-001");

        HttpResponse<String> report = send("GET", "/report", true, null);
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).doesNotContain("ORDERSDB");
        ReportChecks.assertNoHostileValue(report.body());
        ReportChecks.assertSchema(report.body());
    }
}
