package io.github.pvgiang.springtune.boot;

import io.github.pvgiang.springtune.model.Knob;
import io.github.pvgiang.springtune.model.Recommendation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Reads a {@link ThreadPoolTaskExecutor} bean (including Boot's {@code applicationTaskExecutor}). */
final class ExecutorComponent implements TunedComponent {

    private final String beanName;
    private final ThreadPoolTaskExecutor executor;
    private final SpringTuneProperties properties;
    private final SampleWindow<ExecutorSample> samples;

    ExecutorComponent(String beanName, ThreadPoolTaskExecutor executor, SpringTuneProperties properties) {
        this.beanName = beanName;
        this.executor = executor;
        this.properties = properties;
        this.samples = new SampleWindow<>(properties.getExecutor().getWindow().plus(properties.getSampling().getInterval()));
    }

    @Override
    public String id() {
        return "executor:" + beanName;
    }

    private String knobPrefix() {
        return "executor." + beanName;
    }

    @Override
    public List<Knob> knobs() {
        String source = "ThreadPoolTaskExecutor";
        return List.of(
                new Knob(knobPrefix() + ".corePoolSize", id(), "corePoolSize", executor.getCorePoolSize(), null, 1L, null,
                        "count", source),
                new Knob(knobPrefix() + ".maxPoolSize", id(), "maxPoolSize", executor.getMaxPoolSize(), null, 1L, null,
                        "count", source),
                new Knob(knobPrefix() + ".queueCapacity", id(), "queueCapacity", queueCapacity(), null, 0L, null,
                        "count", source));
    }

    private long queueCapacity() {
        ThreadPoolExecutor pool = poolOrNull();
        if (pool == null) {
            return -1;
        }
        BlockingQueue<Runnable> queue = pool.getQueue();
        long capacity = (long) queue.size() + queue.remainingCapacity();
        return Math.min(capacity, Integer.MAX_VALUE);
    }

    private ThreadPoolExecutor poolOrNull() {
        try {
            return executor.getThreadPoolExecutor();
        } catch (IllegalStateException notInitialized) {
            return null;
        }
    }

    @Override
    public Map<String, Object> signals() {
        Map<String, Object> signals = new LinkedHashMap<>();
        ExecutorSample latest = samples.latest();
        if (latest == null) {
            signals.put("status", "executor not initialized yet");
            return signals;
        }
        signals.put("sampledAt", latest.at().toString());
        signals.put("queued", latest.queued());
        signals.put("active", latest.active());
        signals.put("poolSize", latest.poolSize());
        return signals;
    }

    @Override
    public int sampleCount() {
        return samples.size();
    }

    @Override
    public void sample(Instant now) {
        ThreadPoolExecutor pool = poolOrNull();
        if (pool == null) {
            return;
        }
        samples.add(new ExecutorSample(now, pool.getQueue().size(), (int) queueCapacity(), pool.getActiveCount(),
                pool.getPoolSize(), pool.getCorePoolSize(), pool.getMaximumPoolSize()));
    }

    @Override
    public List<Recommendation> recommend(Instant now) {
        return ExecutorAdvisor.advise(knobPrefix(),
                samples.covering(now, properties.getExecutor().getWindow(), properties.getSampling().getInterval()),
                properties.getExecutor(), now).map(List::of).orElse(List.of());
    }
}
