package io.github.winky.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Decision;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Real Tomcat with the {@code DispatcherServlet} at {@code /api} and Boot's additional probe paths. Raw sockets,
 * so that paths reach the server exactly as written (no client-side normalization).
 */
@SpringBootTest(classes = ServletTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springtune.limiter.enabled=true", "spring.mvc.servlet.path=/api",
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.probes.add-additional-paths=true"})
class ServletPathBypassTest {

    @Autowired
    private Environment environment;

    @Autowired
    private AdaptiveLimiter limiter;

    private String statusLine(String path) throws Exception {
        try (Socket socket = new Socket("localhost", Integer.parseInt(environment.getProperty("local.server.port")))) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.setSoTimeout(10_000);
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    private long limited() {
        return limiter.count(Decision.ACCEPTED) + limiter.count(Decision.SHADOW_REJECTED)
                + limiter.count(Decision.REJECTED);
    }

    private void assertBypassed(String path) throws Exception {
        long bypassed = limiter.count(Decision.BYPASSED);
        long limited = limited();
        assertThat(statusLine(path)).as(path).startsWith("HTTP/1.1 200");
        assertThat(limiter.count(Decision.BYPASSED)).as(path + " bypassed").isEqualTo(bypassed + 1);
        assertThat(limited()).as(path + " limited").isEqualTo(limited);
    }

    @Test
    void probesBelowTheServletPathAndAdditionalProbePathsAreNeverLimited() throws Exception {
        assertBypassed("/api/actuator/health");
        assertBypassed("/api/actuator/health/liveness");
        assertBypassed("/api/livez");
        assertBypassed("/api/readyz");
        long limited = limited();
        assertThat(statusLine("/api/work")).startsWith("HTTP/1.1 200");
        assertThat(limited()).isEqualTo(limited + 1);
    }

    @Test
    void traversalThroughTheActuatorPathDoesNotReachAHandler() throws Exception {
        // The policy sees the raw URI, like Spring MVC's PathPattern mapping: no handler matches, so prefixing
        // the actuator path cannot be used to reach a limited endpoint unlimited.
        for (String path : new String[] {"/api/actuator/../work", "/api/actuator/%2e%2e/work",
                "/api/actuator/..;/work"}) {
            assertThat(statusLine(path)).as(path).doesNotStartWith("HTTP/1.1 200");
        }
    }
}
