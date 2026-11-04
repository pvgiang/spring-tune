package io.github.winky.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A misconfigured application that permits every request (Spring Security's anonymous authentication on):
 * the write operation still refuses the call, because an anonymous caller is not an authenticated principal.
 */
class ApplyPermitAllWebTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).csrf(csrf -> csrf.disable());
            return http.build();
        }
    }

    private static ConfigurableApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(App.class).properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "management.endpoints.web.exposure.include=tuning",
                "springtune.mode=apply").run();
        base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/actuator/tuning";
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousCallerIsRefusedEvenWhenSecurityPermitsIt() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"freeze\",\"reason\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"NOT_AUTHENTICATED\"").contains("\"who\":\"anonymous\"");
        assertThat(context.getBean(ChangeController.class).frozen()).isFalse();
    }
}
