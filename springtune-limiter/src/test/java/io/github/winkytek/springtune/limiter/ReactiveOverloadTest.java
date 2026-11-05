package io.github.winkytek.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Decision;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** WebFlux on real Netty: the overload scenario at a smaller scale, and client cancellation. */
class ReactiveOverloadTest {

    static final int CALLERS = 80;

    static final Duration DURATION = Duration.ofSeconds(3);

    private static ConfigurableApplicationContext start(String... properties) {
        List<String> all = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off",
                "springtune.limiter.min-limit=4", "springtune.limiter.warmup-samples=50", ServerStats.PERCENTILES));
        all.addAll(List.of(properties));
        return ReactiveTestApplication.start(all.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext context) {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    private static LoadGenerator.Result measure(ConfigurableApplicationContext context, String label,
            java.util.function.IntSupplier limit) throws InterruptedException {
        LoadGenerator.run("warm-up", port(context), "/work", CALLERS, Duration.ofMillis(1500), limit);
        ServerStats.reset(context);
        return LoadGenerator.run(label, port(context), "/work", CALLERS, DURATION, limit);
    }

    @Test
    void enforceRejectsFastAndKeepsLatencyBounded() throws Exception {
        LoadGenerator.Result off;
        ServerStats offServer;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=false")) {
            assertThat(ReactiveTestApplication.webServerType(context)).isEqualTo("NettyWebServer");
            off = measure(context, "webflux, limiter off", () -> -1);
            offServer = ServerStats.of(context, "/work");
        }
        System.out.println(off.summary() + " | " + offServer.summary());

        LoadGenerator.Result enforce;
        ServerStats enforceServer;
        AdaptiveLimiter limiter;
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true",
                "springtune.limiter.enforce=true")) {
            assertThat(ReactiveTestApplication.webServerType(context)).isEqualTo("NettyWebServer");
            limiter = context.getBean(AdaptiveLimiter.class);
            enforce = measure(context, "webflux, enforce " + context.getEnvironment().getProperty("springtune.limiter.algorithm", "vegas"), limiter::limit);
            Await.noInflight(limiter);
            enforceServer = ServerStats.of(context, "/work");
        }
        System.out.println(enforce.summary() + " | " + enforceServer.summary());

        assertThat(off.rejected()).isZero();
        assertThat(limiter.inflight()).as("permits leaked").isZero();
        assertThat(enforce.errors()).isZero();
        assertThat(enforce.other()).isZero();
        assertThat(enforce.ok()).isPositive();
        assertThat(enforce.rejected()).isPositive();
        assertThat(enforce.rejectedWithRetryAfter()).isEqualTo(enforce.rejected());
        assertThat(enforceServer.rejected()).isEqualTo(enforce.rejected());
        assertThat(enforceServer.okP99Millis()).isLessThan(offServer.okP99Millis() * 3 / 4);
        assertThat(enforce.limitSamples().get(enforce.limitSamples().size() - 1)).isLessThan(CALLERS / 2);
        assertThat(enforce.healthStatuses()).isNotEmpty().containsOnly(200);
    }

    @Test
    void clientCancellationReleasesThePermit() throws Exception {
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true",
                "springtune.limiter.enforce=true")) {
            AdaptiveLimiter limiter = context.getBean(AdaptiveLimiter.class);
            long samples = limiter.completedSamples();
            try (Socket socket = new Socket("localhost", port(context))) {
                OutputStream out = socket.getOutputStream();
                out.write("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Await.until(() -> limiter.inflight() == 1);
                assertThat(limiter.inflight()).isEqualTo(1);
            } // client goes away; /slow would answer only after 30 s
            Await.noInflight(limiter);
            assertThat(limiter.inflight()).isZero();
            assertThat(limiter.count(Decision.ACCEPTED)).isEqualTo(1);
            // CANCEL is IGNORE: the permit came back without a (meaningless) latency sample.
            assertThat(limiter.completedSamples()).isEqualTo(samples);
        }
    }

    @Test
    void sseAndActuatorAreBypassed() throws Exception {
        try (ConfigurableApplicationContext context = start("springtune.limiter.enabled=true",
                "springtune.limiter.enforce=true")) {
            AdaptiveLimiter limiter = context.getBean(AdaptiveLimiter.class);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + port(context);
            int health = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base
                    + "/actuator/health")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            int sse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/work"))
                    .header("Accept", "text/event-stream").build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            assertThat(health).isEqualTo(200);
            assertThat(sse).isEqualTo(200);
            assertThat(limiter.count(Decision.BYPASSED)).isEqualTo(2);
            assertThat(limiter.count(Decision.ACCEPTED)).isZero();
        }
    }
}
