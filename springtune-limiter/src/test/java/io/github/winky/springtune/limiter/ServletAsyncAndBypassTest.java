package io.github.winky.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Decision;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Real Tomcat with the limit pinned to 1 (enforce, no warmup), so a single held permit makes the next limited
 * request a rejection and every permit leak is visible.
 */
@SpringBootTest(classes = ServletTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springtune.limiter.enabled=true", "springtune.limiter.enforce=true",
                "springtune.limiter.initial-limit=1", "springtune.limiter.min-limit=1",
                "springtune.limiter.max-limit=1", "springtune.limiter.warmup-samples=0"})
class ServletAsyncAndBypassTest {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    private Environment environment;

    @Autowired
    private AdaptiveLimiter limiter;

    @Autowired
    private ServletTestApplication.AsyncController async;

    @AfterEach
    void noLeak() throws InterruptedException {
        Await.noInflight(limiter);
        assertThat(limiter.inflight()).as("permits leaked").isZero();
    }

    private HttpRequest get(String path, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(20));
        if (accept != null) {
            builder.header("Accept", accept);
        }
        return builder.build();
    }

    private CompletableFuture<HttpResponse<String>> send(String path, String accept) {
        return client.sendAsync(get(path, accept), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deferredResultHoldsItsPermitUntilCompletedWhileProbesAndStreamsAreServed() throws Exception {
        async.held.set(null);
        CompletableFuture<HttpResponse<String>> pending = send("/async/held", null);
        Await.until(() -> async.held.get() != null && limiter.inflight() == 1);
        assertThat(limiter.inflight()).isEqualTo(1);

        // The only permit is taken: limited requests are rejected with status, Retry-After and a tiny body ...
        HttpResponse<String> rejected = send("/work", null).get(10, TimeUnit.SECONDS);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(rejected.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        assertThat(rejected.body()).isEqualTo("Too many concurrent requests, retry later.\n");

        // ... but health probes and SSE are never limited.
        long bypassed = limiter.count(Decision.BYPASSED);
        assertThat(send("/actuator/health", null).get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        HttpResponse<String> sse = send("/sse", "text/event-stream").get(10, TimeUnit.SECONDS);
        assertThat(sse.statusCode()).isEqualTo(200);
        assertThat(sse.body()).contains("tick-0").contains("tick-2");
        assertThat(limiter.count(Decision.BYPASSED)).isEqualTo(bypassed + 2);
        assertThat(limiter.inflight()).isEqualTo(1);

        long samples = limiter.completedSamples();
        DeferredResult<String> result = async.held.get();
        result.setResult("released");
        assertThat(pending.get(10, TimeUnit.SECONDS).body()).isEqualTo("released");
        Await.noInflight(limiter);
        assertThat(limiter.inflight()).isZero();
        assertThat(limiter.completedSamples()).isEqualTo(samples + 1);

        // The permit is back: the next request is accepted.
        assertThat(send("/work", null).get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void deferredResultTimeoutReleasesThePermitAsDropped() throws Exception {
        long samples = limiter.completedSamples();
        CompletableFuture<HttpResponse<String>> pending = send("/async/never", null);
        Await.until(() -> limiter.inflight() == 1 || pending.isDone());
        Thread.sleep(200); // well after the filter returned, well before the 1 s async timeout
        assertThat(pending).isNotDone();
        assertThat(limiter.inflight()).as("held across the async cycle").isEqualTo(1);
        HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
        // Spring MVC answers an async timeout with 503; that alone would be IGNORE (no sample). The +1 sample
        // shows the timeout reached the limiter first, as DROPPED.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).isEmpty(); // not one of our rejections
        Await.noInflight(limiter);
        assertThat(limiter.completedSamples()).isEqualTo(samples + 1);
        assertThat(send("/work", null).get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void callableHoldsItsPermitUntilDone() throws Exception {
        CompletableFuture<HttpResponse<String>> pending = send("/async/callable", null);
        Await.until(() -> limiter.inflight() == 1 || pending.isDone());
        assertThat(limiter.inflight()).isEqualTo(1);
        assertThat(send("/work", null).get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        assertThat(pending.get(10, TimeUnit.SECONDS).body()).isEqualTo("done");
        Await.noInflight(limiter);
        assertThat(send("/work", null).get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void webSocketUpgradeIsBypassed() throws Exception {
        long bypassed = limiter.count(Decision.BYPASSED);
        // java.net.http forbids setting Upgrade, so send the handshake headers over a raw socket.
        try (java.net.Socket socket = new java.net.Socket("localhost",
                Integer.parseInt(environment.getProperty("local.server.port")))) {
            socket.getOutputStream().write(("GET /work HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\n"
                    + "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.setSoTimeout(10_000);
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            assertThat(statusLine).startsWith("HTTP/1.1 ");
        }
        assertThat(limiter.count(Decision.BYPASSED)).isEqualTo(bypassed + 1);
    }
}
