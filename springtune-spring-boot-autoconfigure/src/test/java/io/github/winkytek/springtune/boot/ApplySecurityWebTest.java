package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * Apply mode in a real Boot web application with actuator and Spring Security: the write operation is
 * authenticated by the application's security, {@code springtune.apply.required-role} is enforced by
 * SpringTune, and the principal is recorded in the audit.
 */
class ApplySecurityWebTest {

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
                    User.withUsername("ops").password("{noop}ops-pw").roles("TUNER").build(),
                    User.withUsername("viewer").password("{noop}viewer-pw").roles("VIEWER").build());
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
                "management.endpoints.web.exposure.include=tuning",
                "springtune.mode=apply",
                "springtune.apply.required-role=TUNER").run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/actuator/tuning";
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    private HttpResponse<String> send(String method, String user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            String credentials = user + ":" + user + "-pw";
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void writeOperationIsAuthenticatedRoleCheckedAndAudited() throws Exception {
        String apply = "{\"action\":\"apply\",\"ruleId\":\"ST-REC-HIKARI-001\","
                + "\"knobId\":\"hikari.orders.maximumPoolSize\",\"expectedCurrent\":2,\"reason\":\"test\"}";
        assertThat(send("POST", null, apply).statusCode()).isEqualTo(401);
        assertThat(send("GET", null, null).statusCode()).isEqualTo(401);

        HttpResponse<String> viewer = send("POST", "viewer", apply);
        assertThat(viewer.statusCode()).isEqualTo(403);
        assertThat(viewer.body()).contains("\"code\":\"ROLE_REQUIRED\"").contains("\"who\":\"viewer\"");

        // Authorized, but there is no such knob in this application: refused by the guard, audited with the caller.
        HttpResponse<String> unknown = send("POST", "ops", apply);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"code\":\"UNKNOWN_KNOB\"").contains("\"who\":\"ops\"");

        HttpResponse<String> invalid = send("POST", "ops", "{\"action\":\"delete\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"code\":\"INVALID_REQUEST\"");

        HttpResponse<String> freeze = send("POST", "ops", "{\"action\":\"freeze\",\"reason\":\"incident 42\"}");
        assertThat(freeze.statusCode()).isEqualTo(200);
        assertThat(freeze.body()).contains("\"outcome\":\"FROZEN\"").contains("\"who\":\"ops\"")
                .contains("\"why\":\"incident 42\"");

        HttpResponse<String> snapshot = send("GET", "ops", null);
        assertThat(snapshot.statusCode()).isEqualTo(200);
        assertThat(snapshot.body()).contains("\"mode\":\"APPLY\"").contains("\"applySupported\":true")
                .contains("\"frozen\":true").contains("\"requiredRole\":\"TUNER\"").contains("\"who\":\"viewer\"")
                .contains("\"outcome\":\"FROZEN\"");
        assertThat(context.getBean(ChangeController.class).audit()).hasSize(4);
    }

    /** The report selector is a read operation next to the write extension: neither shadows the other. */
    @Test
    void reportIsReadableAndSecuredLikeTheSnapshot() throws Exception {
        String root = base;
        try {
            base = root + "/report";
            assertThat(send("GET", null, null).statusCode()).isEqualTo(401);
            HttpResponse<String> report = send("GET", "viewer", null);
            assertThat(report.statusCode()).isEqualTo(200);
            assertThat(report.body()).contains("\"format\":\"springtune-report/1\"").contains("\"mode\":\"APPLY\"")
                    .doesNotContain("viewer").doesNotContain("\"who\"");
            assertThat(send("POST", "ops", "{\"action\":\"freeze\"}").statusCode()).as("no write operation here")
                    .isIn(404, 405);
            base = root + "/unknown";
            assertThat(send("GET", "ops", null).statusCode()).isEqualTo(404);
        } finally {
            base = root;
        }
    }
}
