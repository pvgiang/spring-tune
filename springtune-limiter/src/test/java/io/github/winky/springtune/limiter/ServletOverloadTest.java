package io.github.winky.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Decision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Real Tomcat, real Netflix limiter, real HTTP clients: {@value #CALLERS} closed-loop callers against an endpoint
 * whose downstream serves 10 at a time (20 ms each). The same load runs with the limiter off, in enforce mode and
 * in shadow mode. Assertions are on direction and generous bounds, so they hold on a slow CI runner; the measured
 * numbers are printed (they are quoted in docs/limiter.md).
 *
 * <p>{@code min-limit} is lowered to 4 (default 10, which happens to equal the downstream capacity here) so the
 * limit the algorithm finds is its own, not the floor.
 */
class ServletOverloadTest {

    static final int CALLERS = 160;

    static final Duration DURATION = Duration.ofSeconds(4);

    static final String ALGORITHM = System.getProperty("springtune.test.algorithm", "vegas");

    static final String MIN_LIMIT = System.getProperty("springtune.test.min-limit", "4");

    private static ConfigurableApplicationContext start(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off",
                "springtune.limiter.algorithm=" + ALGORITHM, "springtune.limiter.min-limit=" + MIN_LIMIT,
                "springtune.limiter.warmup-samples=50", ServerStats.PERCENTILES));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(ServletTestApplication.class).web(WebApplicationType.SERVLET)
                .properties(all.toArray(String[]::new)).run();
    }

    /**
     * 1.5 s of the same load, not measured: JIT, connection set-up and (with the limiter) warmup-samples and the
     * first adaptation. Server-side timers are reset afterwards.
     */
    private static void warmUp(ConfigurableApplicationContext context, java.util.function.IntSupplier limit)
            throws InterruptedException {
        LoadGenerator.run("warm-up", port(context), "/work", CALLERS, Duration.ofMillis(1500), limit);
        ServerStats.reset(context);
    }

    private static int port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @Test
    void enforceRejectsFastKeepsLatencyBoundedAndHealthUp() throws Exception {
        LoadGenerator.Result off;
        ServerStats offServer;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=false")) {
            assertThat(context.getBeansOfType(AdaptiveLimiter.class)).isEmpty();
            warmUp(context, () -> -1);
            off = LoadGenerator.run("servlet, limiter off", port(context), "/work", CALLERS, DURATION, () -> -1);
            offServer = ServerStats.of(context, "/work");
        }
        System.out.println(off.summary() + " | " + offServer.summary());

        LoadGenerator.Result enforce;
        ServerStats enforceServer;
        AdaptiveLimiter limiter;
        long rejectedDuringRun;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true",
                "springtune.limiter.enforce=true")) {
            limiter = context.getBean(AdaptiveLimiter.class);
            warmUp(context, limiter::limit);
            long rejectedInWarmUp = limiter.count(Decision.REJECTED);
            enforce = LoadGenerator.run("servlet, enforce " + ALGORITHM, port(context), "/work", CALLERS, DURATION,
                    limiter::limit);
            Await.noInflight(limiter);
            rejectedDuringRun = limiter.count(Decision.REJECTED) - rejectedInWarmUp;
            enforceServer = ServerStats.of(context, "/work");
        }
        System.out.println(enforce.summary() + " | " + enforceServer.summary());

        // Baseline really is overloaded: everything queues on the downstream.
        assertThat(off.rejected()).isZero();
        assertThat(offServer.okP99Millis()).isGreaterThan(4.0 * ServletTestApplication.FakeDownstream.SERVICE_MILLIS);

        assertThat(limiter.inflight()).as("permits leaked").isZero();
        assertThat(enforce.errors()).isZero();
        assertThat(enforce.other()).isZero();
        assertThat(enforce.ok()).isPositive();
        assertThat(enforce.rejected()).isPositive();
        assertThat(enforce.rejectedWithRetryAfter()).isEqualTo(enforce.rejected());
        assertThat(rejectedDuringRun).isEqualTo(enforce.rejected());
        // Boot's observation filter runs outside the limiter: every rejection is in http.server.requests.
        assertThat(enforceServer.rejected()).isEqualTo(enforce.rejected());
        // Accepted requests no longer wait behind the whole queue. Asserted on the server side only: the
        // client-side p99 shares 4 vCPUs with 160 caller threads and flaked on a loaded machine.
        assertThat(enforceServer.okP99Millis()).isLessThan(offServer.okP99Millis() / 2);
        // The limit stays well below the offered concurrency (and never under min-limit).
        assertThat(enforce.limitSamples()).allSatisfy(l -> assertThat(l).isBetween(Integer.parseInt(MIN_LIMIT), 200));
        assertThat(enforce.limitSamples().get(enforce.limitSamples().size() - 1)).isLessThan(CALLERS / 2);
        // Probes are never limited and never starve.
        assertThat(enforce.healthStatuses()).isNotEmpty().containsOnly(200);
    }

    @Test
    void shadowNeverRejectsButCountsWouldBeRejections() throws Exception {
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true")) {
            AdaptiveLimiter limiter = context.getBean(AdaptiveLimiter.class);
            LoadGenerator.Result shadow = LoadGenerator.run("servlet, shadow " + ALGORITHM, port(context), "/work",
                    CALLERS, Duration.ofSeconds(3), limiter::limit);
            Await.noInflight(limiter);
            System.out.println(shadow.summary() + " | shadow_rejected=" + limiter.count(Decision.SHADOW_REJECTED));

            assertThat(shadow.rejected()).isZero();
            assertThat(shadow.other()).isZero();
            assertThat(shadow.errors()).isZero();
            assertThat(limiter.count(Decision.REJECTED)).isZero();
            assertThat(limiter.count(Decision.SHADOW_REJECTED)).isPositive();
            assertThat(limiter.count(Decision.BYPASSED)).isGreaterThanOrEqualTo(shadow.healthStatuses().size());
            assertThat(shadow.healthStatuses()).isNotEmpty().containsOnly(200);
            assertThat(limiter.inflight()).isZero();
        }
    }
}
