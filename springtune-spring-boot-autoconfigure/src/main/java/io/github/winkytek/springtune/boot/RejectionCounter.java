package io.github.winkytek.springtune.boot;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts rejected tasks, then delegates to the executor's original policy (abort, caller-runs, ...), so
 * behavior is unchanged. Installed only in apply mode, where rejections are the executor's verification
 * signal (rule R6).
 */
final class RejectionCounter implements RejectedExecutionHandler {

    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejected = new AtomicLong();

    private RejectionCounter(RejectedExecutionHandler delegate) {
        this.delegate = delegate;
    }

    /** Wraps the pool's current handler once; returns the counter in place. */
    static RejectionCounter install(ThreadPoolExecutor pool) {
        synchronized (pool) {
            RejectedExecutionHandler current = pool.getRejectedExecutionHandler();
            if (current instanceof RejectionCounter counter) {
                return counter;
            }
            RejectionCounter counter = new RejectionCounter(current);
            pool.setRejectedExecutionHandler(counter);
            return counter;
        }
    }

    long rejected() {
        return rejected.get();
    }

    RejectedExecutionHandler delegate() {
        return delegate;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        rejected.incrementAndGet();
        delegate.rejectedExecution(task, executor);
    }
}
