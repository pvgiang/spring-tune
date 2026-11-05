package io.github.winkytek.springtune.loadtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A replica under test: a real Spring Boot application ({@link ReplicaApp}) on a random port with its own Hikari
 * pool (pool {@code app}, {@code ApplicationName=<name>}, its own role), in {@code springtune.mode=apply} with
 * compressed timings. The test is the operator: it reads {@code GET /actuator/tuning} and applies through
 * {@code POST /actuator/tuning} with {@code expectedCurrent} and {@code expectedTarget}, as a human would.
 */
final class Replica implements AutoCloseable {

    static final String KNOB = "hikari.app.maximumPoolSize";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A response of {@code POST /actuator/tuning}. */
    record ApplyResult(int status, String code, String changeId, String outcome, JsonNode body) {
    }

    private final String name;
    private final ConfigurableApplicationContext context;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String authorization = "Basic " + Base64.getEncoder().encodeToString(
            (ReplicaApp.OPERATOR + ":" + ReplicaApp.OPERATOR_PASSWORD).getBytes(StandardCharsets.UTF_8));

    private Replica(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
        this.endpoint = URI.create("http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                + "/actuator/tuning");
    }

    /** The timings every replica uses unless a test overrides them (docs/loadtests.md, "Compressed timings"). */
    static Map<String, String> defaults(int pool, int databaseMaxConnections, int maxReplicas) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("spring.datasource.hikari.pool-name", "app");
        p.put("spring.datasource.hikari.maximum-pool-size", String.valueOf(pool));
        p.put("spring.datasource.hikari.minimum-idle", String.valueOf(pool));
        p.put("spring.datasource.hikari.connection-timeout", "10000");
        p.put("springtune.mode", "apply");
        p.put("springtune.apply.required-role", "TUNER");
        p.put("springtune.sampling.interval", "1s");
        p.put("springtune.hikari.window", "15s");
        p.put("springtune.apply.baseline", "10s");
        p.put("springtune.apply.warmup", "3s");
        p.put("springtune.apply.verification-window", "20s");
        p.put("springtune.apply.cooldown", "10s");
        p.put("springtune.apply.cooldown-after-revert", "10m");
        p.put("springtune.budget.database-max-connections", String.valueOf(databaseMaxConnections));
        p.put("springtune.budget.max-replicas", String.valueOf(maxReplicas));
        p.put("springtune.budget.safety-factor", "0.8");
        return p;
    }

    static Replica start(String name, String jdbcUrl, String role, String password, Map<String, String> settings) {
        List<String> props = new ArrayList<>();
        props.add("spring.application.name=" + name);
        props.add("server.port=0");
        props.add("spring.main.banner-mode=off");
        props.add("spring.jmx.enabled=false");
        props.add("management.endpoints.web.exposure.include=tuning");
        props.add("spring.datasource.url=" + jdbcUrl);
        props.add("spring.datasource.username=" + role);
        props.add("spring.datasource.password=" + password);
        settings.forEach((k, v) -> props.add(k + "=" + v));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ReplicaApp.class)
                .properties(props.toArray(String[]::new)).run();
        return new Replica(name, context);
    }

    String name() {
        return name;
    }

    HikariDataSource dataSource() {
        return context.getBean(HikariDataSource.class);
    }

    int maximumPoolSize() {
        return dataSource().getHikariConfigMXBean().getMaximumPoolSize();
    }

    JsonNode snapshot() {
        HttpRequest request = HttpRequest.newBuilder(endpoint).header("Authorization", authorization).GET()
                .timeout(Duration.ofSeconds(10)).build();
        return send(request).body();
    }

    /** The current recommendation for the pool's maximumPoolSize, if any. */
    Optional<JsonNode> recommendation() {
        for (JsonNode rec : snapshot().path("recommendations")) {
            if (KNOB.equals(rec.path("knobId").asText())) {
                return Optional.of(rec);
            }
        }
        return Optional.empty();
    }

    /** The apply plan shown for the current recommendation, if any. */
    Optional<JsonNode> applyPlan() {
        for (JsonNode plan : snapshot().path("applyPlans")) {
            if (KNOB.equals(plan.path("knobId").asText())) {
                return Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    /** Applies the recommendation the operator reviewed, compare-and-set on its current and target values. */
    ApplyResult apply(JsonNode reviewed, String reason) {
        return apply(reviewed.path("ruleId").asText(), reviewed.path("currentValue").asLong(),
                reviewed.path("recommendedValue").asLong(), reason);
    }

    ApplyResult apply(String ruleId, long expectedCurrent, long expectedTarget, String reason) {
        ObjectNode body = JSON.createObjectNode();
        body.put("action", "apply");
        body.put("ruleId", ruleId);
        body.put("knobId", KNOB);
        body.put("expectedCurrent", expectedCurrent);
        body.put("expectedTarget", expectedTarget);
        body.put("reason", reason);
        HttpRequest request = HttpRequest.newBuilder(endpoint).header("Authorization", authorization)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        Response response = send(request);
        JsonNode json = response.body();
        JsonNode change = json.path("change");
        return new ApplyResult(response.status(), json.path("code").asText(null), change.path("id").asText(null),
                change.path("outcome").asText(null), json);
    }

    /** The pool knob's apply-mode state (IDLE, VERIFYING, CONFIRMED, REVERTED, ...). */
    String knobState() {
        for (JsonNode knob : snapshot().path("apply").path("knobs")) {
            if (KNOB.equals(knob.path("knobId").asText())) {
                return knob.path("state").asText();
            }
        }
        throw new IllegalStateException(name + ": knob " + KNOB + " is not writable (pool not started?)");
    }

    /** The audit entry with this id, from {@code GET /actuator/tuning}. */
    Optional<JsonNode> auditEntry(String changeId) {
        for (JsonNode entry : snapshot().path("apply").path("audit")) {
            if (changeId.equals(entry.path("id").asText())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Every audit entry, newest first. */
    List<JsonNode> audit() {
        List<JsonNode> entries = new ArrayList<>();
        snapshot().path("apply").path("audit").forEach(entries::add);
        return entries;
    }

    private record Response(int status, JsonNode body) {
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), JSON.readTree(response.body()));
        } catch (IOException ex) {
            throw new IllegalStateException(name + ": " + request.method() + " " + request.uri() + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
