package io.github.winky.springtune.limiter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * Closed-loop HTTP load: {@code callers} threads each send a request, wait for the answer and send the next one,
 * for {@code duration}. A caller that gets a 503 waits {@value #BACKOFF_MILLIS} ms before trying again (a client honouring back-off,
 * scaled down). A separate thread polls {@code /actuator/health} every 100 ms, and another samples the limit.
 */
final class LoadGenerator {

    static final int BACKOFF_MILLIS = 25;

    record Result(String label, int callers, long durationMillis, int ok, int rejected, int rejectedWithRetryAfter,
            int other, int errors, List<Long> okLatencyMicros, List<Integer> healthStatuses, long healthMaxMillis,
            List<Integer> limitSamples) {

        long percentileMillis(double p) {
            if (okLatencyMicros.isEmpty()) {
                return -1;
            }
            int index = (int) Math.ceil(p / 100.0 * okLatencyMicros.size()) - 1;
            return okLatencyMicros.get(Math.max(0, Math.min(index, okLatencyMicros.size() - 1))) / 1000;
        }

        double okPerSecond() {
            return ok * 1000.0 / durationMillis;
        }

        String summary() {
            return String.format(Locale.ROOT,
                    "[%s] callers=%d %.1fs: ok=%d (%.0f/s) p50=%dms p99=%dms max=%dms | rejected=%d "
                            + "(Retry-After on %d) other=%d errors=%d | health: %d probes, max %dms, statuses %s"
                            + " | limit samples min=%s max=%s last=%s",
                    label, callers, durationMillis / 1000.0, ok, okPerSecond(), percentileMillis(50),
                    percentileMillis(99), percentileMillis(100), rejected, rejectedWithRetryAfter, other, errors,
                    healthStatuses.size(), healthMaxMillis, new java.util.TreeSet<>(healthStatuses),
                    limitSamples.isEmpty() ? "-" : Collections.min(limitSamples),
                    limitSamples.isEmpty() ? "-" : Collections.max(limitSamples),
                    limitSamples.isEmpty() ? "-" : limitSamples.get(limitSamples.size() - 1));
        }
    }

    private LoadGenerator() {
    }

    static Result run(String label, int port, String path, int callers, Duration duration, IntSupplier limit)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).executor(Executors.newCachedThreadPool()).build();
        URI work = URI.create("http://localhost:" + port + path);
        URI health = URI.create("http://localhost:" + port + "/actuator/health");
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger retryAfter = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        List<Integer> healthStatuses = Collections.synchronizedList(new ArrayList<>());
        LongAdder healthMax = new LongAdder();
        List<Integer> limitSamples = Collections.synchronizedList(new ArrayList<>());

        long start = System.nanoTime();
        long end = start + duration.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(callers + 2);
        CountDownLatch done = new CountDownLatch(callers + 2);
        for (int i = 0; i < callers; i++) {
            pool.execute(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder(work).timeout(Duration.ofSeconds(20)).GET().build();
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> response = client.send(request,
                                    HttpResponse.BodyHandlers.ofString());
                            long micros = (System.nanoTime() - t0) / 1000;
                            if (response.statusCode() == 200) {
                                ok.incrementAndGet();
                                latencies.add(micros);
                            } else if (response.statusCode() == 503) {
                                rejected.incrementAndGet();
                                if ("1".equals(response.headers().firstValue("Retry-After").orElse(null))) {
                                    retryAfter.incrementAndGet();
                                }
                                Thread.sleep(BACKOFF_MILLIS);
                            } else {
                                other.incrementAndGet();
                            }
                        } catch (java.io.IOException ex) {
                            errors.incrementAndGet();
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
            long max = 0;
            try {
                HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(20)).GET().build();
                while (System.nanoTime() < end) {
                    long t0 = System.nanoTime();
                    int status;
                    try {
                        status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    } catch (java.io.IOException ex) {
                        status = -1;
                    }
                    max = Math.max(max, (System.nanoTime() - t0) / 1_000_000);
                    healthStatuses.add(status);
                    Thread.sleep(100);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                healthMax.add(max);
                done.countDown();
            }
        });
        pool.execute(() -> {
            try {
                while (System.nanoTime() < end) {
                    limitSamples.add(limit.getAsInt());
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
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        pool.shutdownNow();
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        return new Result(label, callers, elapsed, ok.get(), rejected.get(), retryAfter.get(), other.get(),
                errors.get(), sorted, List.copyOf(healthStatuses), healthMax.sum(), List.copyOf(limitSamples));
    }
}
