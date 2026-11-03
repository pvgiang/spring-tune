package io.github.winky.springtune.boot;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/** Samples on a single daemon thread at {@code springtune.sampling.interval}; starts after all other beans. */
public class TuningSampler implements SmartLifecycle {

    private final TuningService service;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    public TuningSampler(TuningService service, Duration interval) {
        this.service = service;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "springtune-sampler");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        scheduler.scheduleAtFixedRate(this::sampleSafely, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void sampleSafely() {
        try {
            service.sample();
        } catch (Throwable ex) {
            // Never let one failure cancel the schedule (ScheduledExecutorService stops on uncaught exceptions).
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
