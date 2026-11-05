package io.github.winkytek.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RequestPolicyTest {

    @Test
    void defaultsLimitEverythingButActuatorSseAndWebSocket() {
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), new MockEnvironment());
        assertThat(policy.isBypassed("/orders/1", 8080, "application/json", null)).isFalse();
        assertThat(policy.isBypassed("", 8080, null, null)).isFalse();
        assertThat(policy.isBypassed("/actuator/health", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/actuator", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/events", 8080, "text/event-stream", null)).isTrue();
        assertThat(policy.isBypassed("/events", 8080, "application/json, TEXT/EVENT-STREAM;q=0.9", null)).isTrue();
        assertThat(policy.isBypassed("/ws", 8080, null, "websocket")).isTrue();
        assertThat(policy.isBypassed("/ws", 8080, null, "WebSocket")).isTrue();
    }

    @Test
    void customActuatorBasePathIsAlwaysExcluded() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.base-path", "/manage");
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), environment);
        assertThat(policy.isBypassed("/manage/health", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/actuator/health", 8080, null, null)).isTrue(); // still in exclude-paths
        assertThat(policy.isBypassed("/api", 8080, null, null)).isFalse();
    }

    @Test
    void actuatorBelowTheDispatcherServletPathIsExcluded() {
        // spring.mvc.servlet.path=/api: Spring MVC serves the actuator at /api/actuator/**.
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), new MockEnvironment(), "/api/");
        assertThat(policy.isBypassed("/api/actuator/health/liveness", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/api/orders", 8080, null, null)).isFalse();
    }

    @Test
    void healthIsExcludedWhenEndpointsAreAtTheRoot() {
        RequestPolicy root = new RequestPolicy(new LimiterProperties(),
                new MockEnvironment().withProperty("management.endpoints.web.base-path", "/"));
        assertThat(root.isBypassed("/health", 8080, null, null)).isTrue();
        assertThat(root.isBypassed("/health/readiness", 8080, null, null)).isTrue();
        assertThat(root.isBypassed("/orders", 8080, null, null)).isFalse();

        RequestPolicy mapped = new RequestPolicy(new LimiterProperties(), new MockEnvironment()
                .withProperty("management.endpoints.web.base-path", "")
                .withProperty("management.endpoints.web.path-mapping.health", "healthz"));
        assertThat(mapped.isBypassed("/healthz", 8080, null, null)).isTrue();
    }

    @Test
    void additionalProbePathsAreExcluded() {
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), new MockEnvironment()
                .withProperty("management.endpoint.health.probes.add-additional-paths", "true"));
        assertThat(policy.isBypassed("/livez", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/readyz", 8080, null, null)).isTrue();
        RequestPolicy off = new RequestPolicy(new LimiterProperties(), new MockEnvironment());
        assertThat(off.isBypassed("/livez", 8080, null, null)).isFalse();
    }

    @Test
    void includeAndExcludePaths() {
        LimiterProperties properties = new LimiterProperties();
        properties.setIncludePaths(List.of("/api/**"));
        properties.setExcludePaths(List.of("/api/admin/**"));
        RequestPolicy policy = new RequestPolicy(properties, new MockEnvironment());
        assertThat(policy.isBypassed("/api/orders", 8080, null, null)).isFalse();
        assertThat(policy.isBypassed("/api/admin/reindex", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/static/app.js", 8080, null, null)).isTrue();
        assertThat(policy.isBypassed("/actuator/health", 8080, null, null)).isTrue(); // base path, implicit
    }

    @Test
    void separateManagementPortIsBypassed() {
        MockEnvironment environment = new MockEnvironment().withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "9090");
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), environment);
        assertThat(policy.isBypassed("/anything", 9090, null, null)).isTrue();
        assertThat(policy.isBypassed("/anything", 8080, null, null)).isFalse();
    }

    @Test
    void sameManagementPortIsNotSpecial() {
        MockEnvironment environment = new MockEnvironment().withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8080");
        RequestPolicy policy = new RequestPolicy(new LimiterProperties(), environment);
        assertThat(policy.isBypassed("/anything", 8080, null, null)).isFalse();
    }
}
