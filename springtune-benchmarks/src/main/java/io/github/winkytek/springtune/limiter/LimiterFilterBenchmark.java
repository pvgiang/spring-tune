package io.github.winkytek.springtune.limiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * One limited request through {@link ConcurrencyLimitFilter}: path policy, permit acquire, chain, release with
 * the response status (which feeds the Vegas algorithm, the default). {@code mode=none} calls the same terminal
 * chain without the filter; the filter's cost per request is {@code shadow - none} or {@code enforce - none}.
 *
 * <p>Default limiter settings (Vegas, initial limit 20, min 10, max 200). With 1 or 8 threads in flight the limit is
 * never reached, so shadow and enforce take the same path and nothing is rejected: this measures the overhead
 * paid by every accepted request, not the cost of a rejection. The terminal chain does no work, so the numbers are
 * the filter alone, not a servlet container (request and response objects are reused per thread, as Tomcat
 * recycles its own).
 *
 * <p>In the package of the filter because its constructor is package-private (the auto-configuration builds it).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class LimiterFilterBenchmark {

    @Param({"none", "shadow", "enforce"})
    public String mode;

    private ConcurrencyLimitFilter filter;
    private AdaptiveLimiter limiter;

    @Setup(Level.Trial)
    public void setUp() {
        if ("none".equals(mode)) {
            return;
        }
        LimiterProperties properties = new LimiterProperties();
        properties.setEnabled(true);
        properties.setEnforce("enforce".equals(mode));
        limiter = AdaptiveLimiter.create(properties);
        filter = new ConcurrencyLimitFilter(limiter,
                new RequestPolicy(properties, new StandardEnvironment(), ""), properties);
    }

    /** Per-thread request, response and terminal chain. */
    @State(Scope.Thread)
    public static class Exchange {

        MockHttpServletRequest request;
        MockHttpServletResponse response;
        FilterChain chain;

        @Setup(Level.Trial)
        public void setUp(Blackhole blackhole) {
            request = new MockHttpServletRequest("GET", "/api/orders/42");
            request.addHeader("Accept", "application/json");
            request.setLocalPort(8080);
            response = new MockHttpServletResponse();
            chain = (req, res) -> {
                ((MockHttpServletResponse) res).setStatus(200);
                blackhole.consume(req);
            };
        }
    }

    private void handle(Exchange exchange) throws ServletException, IOException {
        if (filter == null) {
            exchange.chain.doFilter(exchange.request, exchange.response);
        } else {
            filter.doFilter(exchange.request, exchange.response, exchange.chain);
        }
    }

    @Benchmark
    @Threads(1)
    public void request_1thread(Exchange exchange) throws ServletException, IOException {
        handle(exchange);
    }

    @Benchmark
    @Threads(8)
    public void request_8threads(Exchange exchange) throws ServletException, IOException {
        handle(exchange);
    }

    /** Sanity check after a run: no request may have been rejected (the limit is never reached here). */
    @org.openjdk.jmh.annotations.TearDown(Level.Trial)
    public void tearDown() {
        if (limiter != null && limiter.count(AdaptiveLimiter.Decision.REJECTED) > 0) {
            throw new IllegalStateException("unexpected rejections: "
                    + limiter.count(AdaptiveLimiter.Decision.REJECTED));
        }
    }
}
