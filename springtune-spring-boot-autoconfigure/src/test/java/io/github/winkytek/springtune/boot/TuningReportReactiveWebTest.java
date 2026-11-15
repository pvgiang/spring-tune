package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.ClassUtils;

/**
 * {@code GET /actuator/tuning/report} in a real WebFlux application (Reactor Netty, reactive Spring Security), the
 * counterpart of {@link TuningReportAdviseWebTest}: the same hostile snapshot, serialized by the reactive actuator
 * stack, passes the trap check and the position-aware schema check; the selector maps unknown parts to 404 and the
 * application's security refuses anonymous callers.
 */
class TuningReportReactiveWebTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        SecurityWebFilterChain security(ServerHttpSecurity http) {
            return http.authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .build();
        }

        @Bean
        MapReactiveUserDetailsService users() {
            return new MapReactiveUserDetailsService(
                    User.withUsername("ops").password("{noop}ops-pw").roles("OPS").build());
        }

        /** Replaces the auto-configured service: serves the hostile snapshot. */
        @Bean
        TuningService hostileService(SpringTuneProperties properties, ListableBeanFactory beanFactory) {
            return new TuningService(properties, beanFactory, Clock.systemUTC()) {
                @Override
                public TuningSnapshot snapshot() {
                    return TuningReportAdviseWebTest.hostileSnapshot();
                }
            };
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    /** Netty, not the Tomcat that is also on the test classpath (see {@link ApplyReactiveSecurityWebTest}). */
    private static ConfigurableApplicationContext start() {
        ApplicationContextInitializer<ConfigurableApplicationContext> netty = context -> {
            String name = ClassUtils.isPresent("org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory",
                    null) ? "org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory"
                            : "org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory";
            ((BeanDefinitionRegistry) context).registerBeanDefinition("nettyReactiveWebServerFactory",
                    new RootBeanDefinition(ClassUtils.resolveClassName(name, null)));
        };
        return new SpringApplicationBuilder(App.class).web(WebApplicationType.REACTIVE).initializers(netty).properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "management.endpoints.web.exposure.include=tuning").run();
    }

    private HttpResponse<String> get(String url, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (authenticated) {
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("ops:ops-pw".getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reportOverWebFluxKeepsOnlyAllowlistedValues() throws Exception {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getClass().getMethod("getWebServer").invoke(context).getClass().getSimpleName())
                    .isEqualTo("NettyWebServer");
            String base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                    + "/actuator/tuning";

            HttpResponse<String> snapshot = get(base, true);
            assertThat(snapshot.statusCode()).isEqualTo(200);
            assertThat(snapshot.body()).as("the trap is armed").contains(TuningReportAdviseWebTest.POOL)
                    .contains(TuningReportAdviseWebTest.WHO).contains("db.prod.acme.internal");

            HttpResponse<String> report = get(base + "/report", true);
            assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
            assertThat(report.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type)
                    .contains("json"));
            String body = report.body();
            ReportChecks.assertNoHostileValue(body);
            assertThat(ReportChecks.assertSchema(body)).isGreaterThan(30);
            Files.writeString(Path.of("target", "report-example-webflux.json"), body);

            assertThat(body).contains("\"mode\":\"ADVISE\"").contains("\"ref\":\"hikari#1\"")
                    .contains("\"knob\":\"hikari#1.maximumPoolSize\"").doesNotContain("redis")
                    .contains("feedbackUrl\":\"https://github.com/pvgiang/spring-tune/issues/new?template=3-"
                            + "recommendation-feedback.yml&title=");
            Map<?, ?> root = (Map<?, ?>) ReportChecks.parse(body);
            assertThat((List<?>) root.get("recommendations")).as("forged rule id dropped").hasSize(1);
            assertThat((List<?>) root.get("verdicts")).hasSize(2);

            assertThat(get(base + "/report", false).statusCode()).isEqualTo(401);
            assertThat(get(base + "/nope", true).statusCode()).isEqualTo(404);
        }
    }
}
