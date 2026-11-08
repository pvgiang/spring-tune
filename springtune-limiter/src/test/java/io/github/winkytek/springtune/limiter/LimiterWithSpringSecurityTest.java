package io.github.winkytek.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Decision;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The limiter in front of Spring Security (HTTP Basic) on real Tomcat, in enforce mode under overload, with a
 * share of unauthenticated callers in the load ({@value #UNAUTHENTICATED} of {@value #CALLERS}).
 *
 * <p>The limiter filter runs at {@code HIGHEST_PRECEDENCE + 10}, Spring Security's filter chain at {@code -100}:
 * the limiter decides first. So an unauthenticated request takes a permit like any other, gets 503 (not 401) when
 * the limit is full, and its fast 401 is fed back to the algorithm as a successful latency sample. What must hold:
 * only 200/401/503 (never a 500 from a filter interaction), every 503 has {@code Retry-After}, health stays 200, and
 * no permit leaks (inflight back to 0), including for requests Security answered with 401.
 *
 * <p>Runs in its own surefire execution: Spring Security on the classpath would secure every other test app of
 * this module (see the module's pom). The system property is set by that execution only, so a {@code -Dtest=...}
 * run of the default execution (without Spring Security on its classpath) skips this class instead of failing.
 */
@EnabledIfSystemProperty(named = "springtune.test.spring-security", matches = "true")
class LimiterWithSpringSecurityTest {

    static final int CALLERS = 160;

    static final int UNAUTHENTICATED = 16;

    static final Duration DURATION = Duration.ofSeconds(4);

    @Configuration(proxyBeanMethods = false)
    static class SecurityConfiguration {

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll().anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        /**
         * A plain {@link UserDetailsService} with a {@code {noop}} password. Not {@code InMemoryUserDetailsManager}:
         * that one is also a {@code UserDetailsPasswordService}, so Spring Security upgrades the password to BCrypt
         * after the first login, and every later request spends tens of milliseconds of CPU in BCrypt, inside the
         * permit (see docs/limiter.md, "Spring Security in front").
         */
        @Bean
        UserDetailsService users() {
            UserDetails app = User.withUsername("app").password("{noop}app-pw").roles("USER").build();
            return username -> {
                if (!app.getUsername().equals(username)) {
                    throw new UsernameNotFoundException(username);
                }
                return User.withUserDetails(app).build();
            };
        }
    }

    record Result(Map<Integer, Long> authenticated, Map<Integer, Long> unauthenticated, long rejectedWithoutRetryAfter,
            long errors, List<Integer> healthStatuses, List<Integer> limitSamples) {

        long total() {
            return authenticated.values().stream().mapToLong(Long::longValue).sum()
                    + unauthenticated.values().stream().mapToLong(Long::longValue).sum();
        }

        long count(Map<Integer, Long> statuses, int status) {
            return statuses.getOrDefault(status, 0L);
        }

        String summary(String label) {
            return "[" + label + "] authenticated " + authenticated + " | unauthenticated " + unauthenticated
                    + " | 503 without Retry-After " + rejectedWithoutRetryAfter + " | errors " + errors + " | health "
                    + healthStatuses.size() + " probes " + new java.util.TreeSet<>(healthStatuses) + " | limit min="
                    + (limitSamples.isEmpty() ? "-" : Collections.min(limitSamples)) + " max="
                    + (limitSamples.isEmpty() ? "-" : Collections.max(limitSamples));
        }
    }

    /**
     * Closed-loop callers as in {@link LoadGenerator}; the first {@code unauthenticated} send no credentials. After
     * a 503 a caller waits {@value LoadGenerator#BACKOFF_MILLIS} ms; after a 401, 20 ms (a client that gives up and
     * comes back, so fast 401s do not turn into a busy loop).
     */
    private static Result run(int port, int callers, int unauthenticated, Duration duration, AdaptiveLimiter limiter)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).executor(Executors.newCachedThreadPool()).build();
        URI work = URI.create("http://localhost:" + port + "/work");
        URI health = URI.create("http://localhost:" + port + "/actuator/health");
        String basic = "Basic " + Base64.getEncoder().encodeToString("app:app-pw".getBytes(StandardCharsets.UTF_8));
        Map<Integer, LongAdder> authenticated = new ConcurrentHashMap<>();
        Map<Integer, LongAdder> anonymous = new ConcurrentHashMap<>();
        LongAdder withoutRetryAfter = new LongAdder();
        LongAdder errors = new LongAdder();
        List<Integer> healthStatuses = Collections.synchronizedList(new ArrayList<>());
        List<Integer> limitSamples = Collections.synchronizedList(new ArrayList<>());
        long end = System.nanoTime() + duration.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(callers + 2);
        CountDownLatch done = new CountDownLatch(callers + 2);
        AtomicInteger index = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            pool.execute(() -> {
                boolean withCredentials = index.getAndIncrement() >= unauthenticated;
                Map<Integer, LongAdder> statuses = withCredentials ? authenticated : anonymous;
                HttpRequest.Builder builder = HttpRequest.newBuilder(work).timeout(Duration.ofSeconds(20)).GET();
                if (withCredentials) {
                    builder.header("Authorization", basic);
                }
                HttpRequest request = builder.build();
                try {
                    while (System.nanoTime() < end) {
                        try {
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            int status = response.statusCode();
                            statuses.computeIfAbsent(status, s -> new LongAdder()).increment();
                            if (status == 503) {
                                if (!"1".equals(response.headers().firstValue("Retry-After").orElse(null))) {
                                    withoutRetryAfter.increment();
                                }
                                Thread.sleep(LoadGenerator.BACKOFF_MILLIS);
                            } else if (status == 401) {
                                Thread.sleep(20);
                            }
                        } catch (java.io.IOException ex) {
                            errors.increment();
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        pool.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(20)).GET().build();
                while (System.nanoTime() < end) {
                    int status;
                    try {
                        status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    } catch (java.io.IOException ex) {
                        status = -1;
                    }
                    healthStatuses.add(status);
                    Thread.sleep(100);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        pool.execute(() -> {
            try {
                while (System.nanoTime() < end) {
                    limitSamples.add(limiter.limit());
                    Thread.sleep(100);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(duration.toSeconds() + 30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("load did not finish");
        }
        pool.shutdownNow();
        return new Result(snapshot(authenticated), snapshot(anonymous), withoutRetryAfter.sum(), errors.sum(),
                List.copyOf(healthStatuses), List.copyOf(limitSamples));
    }

    private static Map<Integer, Long> snapshot(Map<Integer, LongAdder> statuses) {
        Map<Integer, Long> copy = new TreeMap<>();
        statuses.forEach((status, count) -> copy.put(status, count.sum()));
        return copy;
    }

    /** Server-side count and mean per status ({@code http.server.requests}), for the printed summary only. */
    private static String serverSide(ConfigurableApplicationContext context) {
        io.micrometer.core.instrument.MeterRegistry registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        StringBuilder out = new StringBuilder("server-side");
        registry.find("http.server.requests").timers().forEach(timer -> out.append(' ')
                .append(timer.getId().getTag("uri")).append(' ').append(timer.getId().getTag("status")).append(" x")
                .append(timer.count()).append(String.format(java.util.Locale.ROOT, " mean=%.0fms",
                        timer.mean(TimeUnit.MILLISECONDS))));
        return out.toString();
    }

    private static long limited(AdaptiveLimiter limiter) {
        return limiter.count(Decision.ACCEPTED) + limiter.count(Decision.REJECTED)
                + limiter.count(Decision.SHADOW_REJECTED);
    }

    @Test
    void overloadBehindBasicAuthOnlyAnswers200Or401Or503AndLeaksNoPermit() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ServletTestApplication.class,
                SecurityConfiguration.class).web(WebApplicationType.SERVLET).properties("server.port=0",
                        "spring.main.banner-mode=off", "springtune.limiter.enabled=true",
                        "springtune.limiter.enforce=true", "springtune.limiter.min-limit=4",
                        "springtune.limiter.warmup-samples=50", ServerStats.PERCENTILES).run()) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            AdaptiveLimiter limiter = context.getBean(AdaptiveLimiter.class);

            Result warmUp = run(port, CALLERS, UNAUTHENTICATED, Duration.ofMillis(1500), limiter);
            System.out.println(warmUp.summary("warm-up"));
            Await.noInflight(limiter);
            ServerStats.reset(context);
            long limitedBefore = limited(limiter);
            long bypassedBefore = limiter.count(Decision.BYPASSED);

            Result result = run(port, CALLERS, UNAUTHENTICATED, DURATION, limiter);
            Await.noInflight(limiter);
            System.out.println(result.summary("servlet + Spring Security, enforce") + " | " + serverSide(context));

            assertThat(limiter.inflight()).as("permits leaked").isZero();
            assertThat(result.errors()).isZero();
            assertThat(result.authenticated().keySet()).as("authenticated statuses").containsOnly(200, 503);
            assertThat(result.unauthenticated().keySet()).as("unauthenticated statuses").containsOnly(401, 503);
            assertThat(result.count(result.authenticated(), 200)).isPositive();
            assertThat(result.count(result.authenticated(), 503)).as("overload is shed").isPositive();
            assertThat(result.count(result.unauthenticated(), 401)).isPositive();
            // The limiter runs before Security: when the limit is full, a request without credentials gets 503.
            assertThat(result.count(result.unauthenticated(), 503)).as("unauthenticated, rejected before Security")
                    .isPositive();
            assertThat(result.rejectedWithoutRetryAfter()).isZero();
            // Every /work request, with or without credentials, went through the limiter (and released its permit).
            assertThat(limited(limiter) - limitedBefore).isEqualTo(result.total());
            assertThat(result.healthStatuses()).isNotEmpty().containsOnly(200);
            assertThat(limiter.count(Decision.BYPASSED) - bypassedBefore)
                    .isGreaterThanOrEqualTo(result.healthStatuses().size());
        }
    }
}
