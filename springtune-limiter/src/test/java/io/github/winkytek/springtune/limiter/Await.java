package io.github.winkytek.springtune.limiter;

import java.time.Duration;
import java.util.function.BooleanSupplier;

final class Await {

    private Await() {
    }

    /** Polls (bounded, 10 s) until the condition holds; the caller asserts afterwards. */
    static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    static void noInflight(AdaptiveLimiter limiter) throws InterruptedException {
        until(() -> limiter.inflight() == 0);
    }
}
