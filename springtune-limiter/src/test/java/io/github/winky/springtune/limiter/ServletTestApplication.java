package io.github.winky.springtune.limiter;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC on Tomcat with a fake downstream that has capacity for 10 concurrent calls. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ServletTestApplication.WorkController.class, ServletTestApplication.AsyncController.class})
class ServletTestApplication {

    /**
     * A downstream (database, remote service) that can serve {@code capacity} calls at once in
     * {@code serviceMillis} each. Further callers queue in FIFO order, so latency grows linearly with the
     * concurrency above capacity: 160 concurrent callers wait ~16 × 20 ms = 320 ms.
     */
    static final class FakeDownstream {

        static final int CAPACITY = 10;

        static final int SERVICE_MILLIS = 20;

        private final Semaphore permits = new Semaphore(CAPACITY, true);

        String call() throws InterruptedException {
            permits.acquire();
            try {
                Thread.sleep(SERVICE_MILLIS);
                return "ok";
            } finally {
                permits.release();
            }
        }
    }

    @Bean
    FakeDownstream fakeDownstream() {
        return new FakeDownstream();
    }

    @RestController
    static class WorkController {

        private final FakeDownstream downstream;

        WorkController(FakeDownstream downstream) {
            this.downstream = downstream;
        }

        @GetMapping("/work")
        String work() throws InterruptedException {
            return downstream.call();
        }
    }

    @RestController
    static class AsyncController {

        final AtomicReference<DeferredResult<String>> held = new AtomicReference<>();

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        @GetMapping("/async/held")
        DeferredResult<String> held() {
            DeferredResult<String> result = new DeferredResult<>(30_000L);
            held.set(result);
            return result;
        }

        @GetMapping("/async/never")
        DeferredResult<String> never() {
            return new DeferredResult<>(1000L); // never completed: times out (Spring answers 503)
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("boom"); // propagates through the filter, then ERROR dispatch
        }

        @GetMapping("/async/error")
        DeferredResult<String> asyncError() {
            DeferredResult<String> result = new DeferredResult<>(30_000L);
            scheduler.schedule(() -> result.setErrorResult(new IllegalStateException("async boom")), 100,
                    TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping("/async/callable")
        Callable<String> callable() {
            return () -> {
                Thread.sleep(300);
                return "done";
            };
        }

        @GetMapping(path = "/sse", produces = "text/event-stream")
        SseEmitter sse() {
            SseEmitter emitter = new SseEmitter(10_000L);
            for (int i = 0; i < 3; i++) {
                int n = i;
                scheduler.schedule(() -> {
                    try {
                        emitter.send("tick-" + n);
                        if (n == 2) {
                            emitter.complete();
                        }
                    } catch (IOException ex) {
                        emitter.completeWithError(ex);
                    }
                }, 100L * (i + 1), TimeUnit.MILLISECONDS);
            }
            return emitter;
        }
    }
}
