package io.github.pvgiang.springtune.boot;

import java.time.Instant;

record ExecutorSample(Instant at, int queued, int queueCapacity, int active, int poolSize, int corePoolSize,
        int maxPoolSize) implements SampleWindow.Timed {
}
