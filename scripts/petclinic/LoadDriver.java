import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load driver for Spring PetClinic (SpringTune PetClinic benchmark, docs/benchmarks/petclinic.md).
 *
 * <pre>
 *   java LoadDriver.java --base http://localhost:8080 --rate 50 --duration 450 --seed 61001 --out summary.json
 * </pre>
 *
 * Requests are scheduled on a clock at {@code --rate} per second with exponential inter-arrival jitter drawn from
 * a {@link Random} seeded with {@code --seed}; a request is sent at its time whether or not earlier ones have
 * answered (open loop). The request sequence (times and URLs) is a pure function of the seed and the rate. Mix:
 * 50% owner search, 35% owner details, 15% vets list. Redirects are not followed. At most {@code --max-in-flight}
 * requests are outstanding; a request due while the cap is reached is counted as skipped and never sent.
 *
 * Output: counts of responses by HTTP status, errors by exception type, and skipped requests. Latency is not
 * recorded on purpose: this driver exists to produce a load, not a performance number.
 */
public class LoadDriver {

    private static final String[] LAST_NAMES = {"", "Davis", "Franklin", "Black", "Coleman", "Zzz"};

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --name value pairs, got " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        String base = opts.getOrDefault("base", "http://localhost:8080");
        double rate = Double.parseDouble(opts.getOrDefault("rate", "50"));
        long durationS = Long.parseLong(opts.getOrDefault("duration", "60"));
        long seed = Long.parseLong(opts.getOrDefault("seed", String.valueOf(System.nanoTime())));
        int maxInFlight = Integer.parseInt(opts.getOrDefault("max-in-flight", "2000"));
        long timeoutS = Long.parseLong(opts.getOrDefault("timeout", "30"));
        String out = opts.get("out");

        System.out.printf("LoadDriver: base=%s rate=%.1f/s duration=%ds seed=%d max-in-flight=%d timeout=%ds%n",
                base, rate, durationS, seed, maxInFlight, timeoutS);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newFixedThreadPool(8))
                .build();

        Random random = new Random(seed);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicLong sent = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong completed = new AtomicLong();
        Map<String, AtomicLong> byStatus = new ConcurrentHashMap<>();
        Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        Map<String, AtomicLong> byKind = new ConcurrentHashMap<>();
        int maxObservedInFlight = 0;

        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        long end = start + TimeUnit.SECONDS.toNanos(durationS);
        double nextOffsetNs = 0;
        long scheduled = 0;
        while (true) {
            nextOffsetNs += -Math.log(1 - random.nextDouble()) / rate * 1e9;
            long due = start + (long) nextOffsetNs;
            if (due >= end) {
                break;
            }
            String kind;
            String path;
            double pick = random.nextDouble();
            if (pick < 0.50) {
                kind = "owner-search";
                path = "/owners?lastName=" + LAST_NAMES[random.nextInt(LAST_NAMES.length)];
            } else if (pick < 0.85) {
                kind = "owner-details";
                path = "/owners/" + (1 + random.nextInt(10));
            } else {
                kind = "vets";
                path = "/vets.html";
            }
            long wait;
            while ((wait = due - System.nanoTime()) > 0) {
                LockSupport.parkNanos(wait);
            }
            scheduled++;
            byKind.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
            int now = inFlight.get();
            if (now >= maxInFlight) {
                skipped.incrementAndGet();
                continue;
            }
            maxObservedInFlight = Math.max(maxObservedInFlight, inFlight.incrementAndGet());
            sent.incrementAndGet();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(timeoutS)).GET().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
                if (failure != null) {
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    errors.computeIfAbsent(cause.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
                } else {
                    byStatus.computeIfAbsent(String.valueOf(response.statusCode()), k -> new AtomicLong())
                            .incrementAndGet();
                }
                completed.incrementAndGet();
                inFlight.decrementAndGet();
            });
        }
        long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS + 5);
        while (inFlight.get() > 0 && System.nanoTime() < drainUntil) {
            Thread.sleep(100);
        }
        Instant endedAt = Instant.now();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"seed\": ").append(seed).append(",\n");
        json.append("  \"rate_per_second\": ").append(rate).append(",\n");
        json.append("  \"duration_seconds\": ").append(durationS).append(",\n");
        json.append("  \"max_in_flight\": ").append(maxInFlight).append(",\n");
        json.append("  \"timeout_seconds\": ").append(timeoutS).append(",\n");
        json.append("  \"started_at\": \"").append(startedAt).append("\",\n");
        json.append("  \"ended_at\": \"").append(endedAt).append("\",\n");
        json.append("  \"scheduled\": ").append(scheduled).append(",\n");
        json.append("  \"sent\": ").append(sent.get()).append(",\n");
        json.append("  \"skipped_in_flight_cap\": ").append(skipped.get()).append(",\n");
        json.append("  \"completed\": ").append(completed.get()).append(",\n");
        json.append("  \"unfinished_at_exit\": ").append(inFlight.get()).append(",\n");
        json.append("  \"max_observed_in_flight\": ").append(maxObservedInFlight).append(",\n");
        json.append("  \"scheduled_by_kind\": ").append(toJson(byKind)).append(",\n");
        json.append("  \"responses_by_status\": ").append(toJson(byStatus)).append(",\n");
        json.append("  \"errors_by_type\": ").append(toJson(errors)).append("\n");
        json.append("}\n");
        System.out.print(json);
        if (out != null) {
            try {
                Files.writeString(Path.of(out), json);
            } catch (IOException ex) {
                System.err.println("LoadDriver: cannot write " + out + ": " + ex);
                System.exit(1);
            }
        }
        System.exit(0);
    }

    private static String toJson(Map<String, AtomicLong> counts) {
        StringBuilder sb = new StringBuilder("{");
        new TreeMap<>(counts).forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append('"').append(k).append("\": ").append(v.get());
        });
        return sb.append('}').toString();
    }
}
