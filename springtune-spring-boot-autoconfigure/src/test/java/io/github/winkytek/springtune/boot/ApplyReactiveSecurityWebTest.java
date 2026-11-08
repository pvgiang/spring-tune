package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
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
 * Apply mode in a real WebFlux application (Reactor Netty) with actuator and reactive Spring Security, the
 * counterpart of {@link ApplySecurityWebTest} and {@link ApplyPermitAllWebTest}: the write operation is reachable
 * over WebFlux, unauthenticated calls are refused by the application's security (401), an anonymous principal
 * ({@code anonymous()} + {@code permitAll()}) is refused by SpringTune (401 {@code NOT_AUTHENTICATED}), a caller
 * without {@code springtune.apply.required-role} gets 403 {@code ROLE_REQUIRED}, and an authorized caller is
 * served and recorded as {@code who}.
 */
class ApplyReactiveSecurityWebTest {

    private static final String APPLY = "{\"action\":\"apply\",\"ruleId\":\"ST-REC-HIKARI-001\","
            + "\"knobId\":\"hikari.orders.maximumPoolSize\",\"expectedCurrent\":2,\"reason\":\"test\"}";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SecuredApp {

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
                    User.withUsername("ops").password("{noop}ops-pw").roles("TUNER").build(),
                    User.withUsername("viewer").password("{noop}viewer-pw").roles("VIEWER").build());
        }
    }

    /** Misconfigured: everything permitted, anonymous authentication on (a non-null principal on WebFlux). */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class PermitAllApp {

        @Bean
        SecurityWebFilterChain security(ServerHttpSecurity http) {
            return http.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .anonymous(Customizer.withDefaults())
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .build();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Starts the app on Netty. Tomcat is on this module's test classpath too (servlet tests), and Boot would
     * prefer it for a reactive app; registering the Netty factory up front makes Boot's own factory
     * configurations back off. The factory class moved between Boot 3 and 4, hence the lookup by name.
     */
    private static ConfigurableApplicationContext start(Class<?> app) {
        ApplicationContextInitializer<ConfigurableApplicationContext> netty = context -> {
            String name = ClassUtils.isPresent("org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory",
                    null) ? "org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory"
                            : "org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory";
            ((BeanDefinitionRegistry) context).registerBeanDefinition("nettyReactiveWebServerFactory",
                    new RootBeanDefinition(ClassUtils.resolveClassName(name, null)));
        };
        return new SpringApplicationBuilder(app).web(WebApplicationType.REACTIVE).initializers(netty).properties(
                "server.port=0",
                "spring.main.banner-mode=off",
                "management.endpoints.web.exposure.include=tuning",
                "springtune.mode=apply",
                "springtune.apply.required-role=TUNER").run();
    }

    private static String base(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/actuator/tuning";
    }

    private static String webServerType(ConfigurableApplicationContext context) throws ReflectiveOperationException {
        return context.getClass().getMethod("getWebServer").invoke(context).getClass().getSimpleName();
    }

    private HttpResponse<String> send(String url, String method, String user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
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
    void writeOperationOverWebFluxIsAuthenticatedRoleCheckedAndAudited() throws Exception {
        try (ConfigurableApplicationContext context = start(SecuredApp.class)) {
            assertThat(webServerType(context)).isEqualTo("NettyWebServer");
            String url = base(context);
            ChangeController controller = context.getBean(ChangeController.class);

            // No credentials: refused by the application's security before the endpoint is reached.
            assertThat(send(url, "POST", null, APPLY).statusCode()).isEqualTo(401);
            assertThat(send(url, "GET", null, null).statusCode()).isEqualTo(401);
            assertThat(controller.audit()).isEmpty();

            HttpResponse<String> viewer = send(url, "POST", "viewer", APPLY);
            assertThat(viewer.statusCode()).isEqualTo(403);
            assertThat(viewer.body()).contains("\"code\":\"ROLE_REQUIRED\"").contains("\"who\":\"viewer\"");

            // Authorized, but there is no such knob in this application: refused by the guard, audited with the caller.
            HttpResponse<String> unknown = send(url, "POST", "ops", APPLY);
            assertThat(unknown.statusCode()).isEqualTo(400);
            assertThat(unknown.body()).contains("\"code\":\"UNKNOWN_KNOB\"").contains("\"who\":\"ops\"");

            HttpResponse<String> freeze = send(url, "POST", "ops", "{\"action\":\"freeze\",\"reason\":\"incident 7\"}");
            assertThat(freeze.statusCode()).isEqualTo(200);
            assertThat(freeze.body()).contains("\"outcome\":\"FROZEN\"").contains("\"who\":\"ops\"")
                    .contains("\"why\":\"incident 7\"");
            assertThat(controller.frozen()).isTrue();

            HttpResponse<String> snapshot = send(url, "GET", "ops", null);
            assertThat(snapshot.statusCode()).isEqualTo(200);
            assertThat(snapshot.body()).contains("\"mode\":\"APPLY\"").contains("\"applySupported\":true")
                    .contains("\"frozen\":true").contains("\"requiredRole\":\"TUNER\"");
            assertThat(controller.audit()).extracting(ChangeAuditEntry::who).containsExactly("ops", "ops", "viewer");
            assertThat(controller.audit()).extracting(ChangeAuditEntry::outcome).containsExactly(
                    ChangeAuditEntry.Outcome.FROZEN, ChangeAuditEntry.Outcome.REFUSED, ChangeAuditEntry.Outcome.REFUSED);
        }
    }

    @Test
    void anonymousPrincipalIsNeverACallerEvenWithPermitAll() throws Exception {
        try (ConfigurableApplicationContext context = start(PermitAllApp.class)) {
            assertThat(webServerType(context)).isEqualTo("NettyWebServer");
            String url = base(context);
            ChangeController controller = context.getBean(ChangeController.class);

            // The application lets the request through (GET is served), but the write operation refuses it.
            assertThat(send(url, "GET", null, null).statusCode()).isEqualTo(200);
            for (String body : new String[] { "{\"action\":\"freeze\",\"reason\":\"x\"}", APPLY }) {
                HttpResponse<String> response = send(url, "POST", null, body);
                assertThat(response.statusCode()).as(body).isEqualTo(401);
                assertThat(response.body()).contains("\"code\":\"NOT_AUTHENTICATED\"").contains("\"who\":\"anonymous\"");
            }
            assertThat(controller.frozen()).isFalse();
            // Unauthenticated refusals are logged and published, not kept in the in-memory audit (R10).
            assertThat(controller.audit()).isEmpty();
        }
    }
}
