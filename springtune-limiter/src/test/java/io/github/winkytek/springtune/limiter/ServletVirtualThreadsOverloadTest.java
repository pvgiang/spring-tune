package io.github.winkytek.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Decision;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link ServletOverloadTest} with {@code spring.threads.virtual.enabled=true}: Tomcat runs every request on a new
 * virtual thread, so there is no worker-thread ceiling (200 platform threads by default) and the limiter is the
 * only concurrency cap. {@value #CALLERS} closed-loop callers, more than Tomcat's default 200 workers, against the
 * same downstream (10 at a time, 20 ms each).
 *
 * <p>A probe filter at {@link Ordered#LOWEST_PRECEDENCE} (inside the limiter) counts how many {@code /work}
 * requests are in the handler at once and whether they run on virtual threads. With the limiter off it proves the
 * ceiling is gone (more than 200 at once); in enforce mode, after warm-up, it never exceeds {@code max-limit}.
 * Assertions are on counts and on the server-side p50/p99 off vs enforce with generous ratios, as in
 * {@link ServletOverloadTest} and {@link ReactiveOverloadTest}.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
class ServletVirtualThreadsOverloadTest {

    static final int CALLERS = 240;

    static final int MAX_LIMIT = 60;

    static final Duration DURATION = Duration.ofSeconds(4);

    /** Counts concurrent {@code /work} requests in the handler; runs after the limiter. */
    static final class ConcurrencyProbe implements Filter, Ordered {

        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final LongAdder virtual = new LongAdder();
        final LongAdder platform = new LongAdder();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (!"/work".equals(((HttpServletRequest) request).getRequestURI())) {
                chain.doFilter(request, response);
                return;
            }
            // Thread.isVirtual() is Java 21 API; this module compiles for Java 17.
            if (Thread.currentThread().getClass().getName().equals("java.lang.VirtualThread")) {
                virtual.increment();
            } else {
                platform.increment();
            }
            peak.accumulateAndGet(current.incrementAndGet(), Math::max);
            try {
                chain.doFilter(request, response);
            } finally {
                current.decrementAndGet();
            }
        }

        void resetPeak() {
            peak.set(current.get());
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ConcurrencyProbe concurrencyProbe() {
            return new ConcurrencyProbe();
        }
    }

    private static ConfigurableApplicationContext start(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off",
                "spring.threads.virtual.enabled=true", "springtune.limiter.min-limit=4",
                "springtune.limiter.max-limit=" + MAX_LIMIT, "springtune.limiter.warmup-samples=50",
                ServerStats.PERCENTILES));
        all.addAll(List.of(properties));
        return new SpringApplicationBuilder(ServletTestApplication.class, ProbeConfiguration.class)
                .web(WebApplicationType.SERVLET).properties(all.toArray(String[]::new)).run();
    }

    private static int port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    /** Same unmeasured warm-up as {@link ServletOverloadTest}; resets server timers and the probe's peak. */
    private static void warmUp(ConfigurableApplicationContext context, java.util.function.IntSupplier limit)
            throws InterruptedException {
        LoadGenerator.run("warm-up", port(context), "/work", CALLERS, Duration.ofMillis(1500), limit);
        ServerStats.reset(context);
        context.getBean(ConcurrencyProbe.class).resetPeak();
    }

    /**
     * Needs at least 2 CPUs. Pinned to one CPU ({@code taskset -c 0}) the server has one carrier thread competing
     * with 240 client threads: with the limiter off it still served ~280 requests/s, but in enforce mode it served
     * 20 requests in 4 s (server p50 805 ms) while rejecting ~900/s, whereas {@link ServletOverloadTest} (platform
     * threads) passed pinned. Recorded in docs/limiter.md as a limitation; not asserted.
     */
    @Test
    void enforceCapsConcurrencyWithoutAWorkerCeiling() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2,
                "needs at least 2 CPUs (see docs/limiter.md, virtual threads)");
        LoadGenerator.Result off;
        ServerStats offServer;
        int offPeak;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=false")) {
            ConcurrencyProbe probe = context.getBean(ConcurrencyProbe.class);
            warmUp(context, () -> -1);
            off = LoadGenerator.run("servlet virtual threads, limiter off", port(context), "/work", CALLERS, DURATION,
                    () -> -1);
            offServer = ServerStats.of(context, "/work");
            offPeak = probe.peak.get();
            assertThat(probe.platform.sum()).as("requests on platform threads").isZero();
            assertThat(probe.virtual.sum()).isPositive();
        }
        System.out.println(off.summary() + " | " + offServer.summary() + " | handler peak concurrency " + offPeak);

        LoadGenerator.Result enforce;
        ServerStats enforceServer;
        AdaptiveLimiter limiter;
        long rejectedDuringRun;
        int enforcePeak;
        int inflightPeak;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true",
                "springtune.limiter.enforce=true")) {
            ConcurrencyProbe probe = context.getBean(ConcurrencyProbe.class);
            limiter = context.getBean(AdaptiveLimiter.class);
            warmUp(context, limiter::limit);
            // Until warmup-samples requests completed, requests over the limit pass without a permit (no cap at
            // all on virtual threads). On a starved machine 1.5 s may not be enough: warm up a bit more (bounded).
            for (int i = 0; i < 3 && !limiter.isWarmedUp(); i++) {
                warmUp(context, limiter::limit);
            }
            assertThat(limiter.isWarmedUp()).as("limiter warmed up before the measured run (%s samples)",
                    limiter.completedSamples()).isTrue();
            long shadowInWarmUp = limiter.count(Decision.SHADOW_REJECTED);
            long rejectedInWarmUp = limiter.count(Decision.REJECTED);
            // Samples the limiter's own inflight count every 0.1 ms while the load runs.
            AtomicBoolean sampling = new AtomicBoolean(true);
            AtomicInteger inflightMax = new AtomicInteger();
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    inflightMax.accumulateAndGet(limiter.inflight(), Math::max);
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                }
            }, "inflight-sampler");
            sampler.setDaemon(true);
            sampler.start();
            try {
                enforce = LoadGenerator.run("servlet virtual threads, enforce", port(context), "/work", CALLERS,
                        DURATION, limiter::limit);
            } finally {
                sampling.set(false);
                sampler.join(2000);
            }
            Await.noInflight(limiter);
            rejectedDuringRun = limiter.count(Decision.REJECTED) - rejectedInWarmUp;
            assertThat(limiter.count(Decision.SHADOW_REJECTED)).as("nothing passed without a permit")
                    .isEqualTo(shadowInWarmUp);
            enforceServer = ServerStats.of(context, "/work");
            enforcePeak = probe.peak.get();
            inflightPeak = inflightMax.get();
            assertThat(probe.platform.sum()).as("requests on platform threads").isZero();
        }
        System.out.println(enforce.summary() + " | " + enforceServer.summary() + " | handler peak concurrency "
                + enforcePeak + ", inflight peak " + inflightPeak);

        // Without the limiter nothing caps concurrency: more requests are in the handler than Tomcat's default
        // 200 platform workers could hold, and everything queues on the downstream.
        assertThat(off.rejected()).isZero();
        assertThat(offPeak).as("no worker-thread ceiling").isGreaterThan(200);
        assertThat(offServer.okP99Millis()).isGreaterThan(4.0 * ServletTestApplication.FakeDownstream.SERVICE_MILLIS);

        assertThat(limiter.inflight()).as("permits leaked").isZero();
        assertThat(inflightPeak).as("inflight never above max-limit").isLessThanOrEqualTo(MAX_LIMIT);
        assertThat(enforcePeak).as("requests in the handler at once, after warm-up").isLessThanOrEqualTo(MAX_LIMIT);
        assertThat(enforce.errors()).isZero();
        assertThat(enforce.other()).isZero();
        assertThat(enforce.ok()).isPositive();
        assertThat(enforce.rejected()).isPositive();
        assertThat(enforce.rejectedWithRetryAfter()).isEqualTo(enforce.rejected());
        assertThat(rejectedDuringRun).isEqualTo(enforce.rejected());
        assertThat(enforceServer.rejected()).isEqualTo(enforce.rejected());
        // Accepted requests no longer wait behind the whole queue. The median is cut by far more than 4x. The p99
        // bound is 3/4 (as in ReactiveOverloadTest), not 1/2 as in ServletOverloadTest: on virtual threads the
        // accepted requests' continuations share 4 carrier threads with thousands of rejected requests per second,
        // and the enforce p99 measured 91-385 ms against 535-636 ms off over 14 runs (one above off/2, on a shared
        // machine), while platform threads gave 62-108 ms.
        assertThat(enforceServer.okP50Millis()).isLessThan(offServer.okP50Millis() / 4);
        assertThat(enforceServer.okP99Millis()).isLessThan(offServer.okP99Millis() * 3 / 4);
        assertThat(enforce.limitSamples()).allSatisfy(l -> assertThat(l).isBetween(4, MAX_LIMIT));
        assertThat(enforce.healthStatuses()).isNotEmpty().containsOnly(200);
    }
}
