package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Reads a {@link ThreadPoolTaskExecutor} bean (including Boot's {@code applicationTaskExecutor}). Never calls a
 * size setter: in apply mode the only write path is the {@link ExecutorPoolWriter} of its
 * {@link #writableKnob() writable knob}.
 */
final class ExecutorComponent implements TunedComponent {

    private final String beanName;
    private final ThreadPoolTaskExecutor executor;
    private final SpringTuneProperties properties;
    private final TimerReader timers;
    private final SampleWindow<ExecutorSample> samples;
    private volatile WritableKnob writableKnob;
    private volatile RejectionCounter rejections;

    ExecutorComponent(String beanName, ThreadPoolTaskExecutor executor, SpringTuneProperties properties) {
        this(beanName, executor, properties, TimerReader.NONE);
    }

    ExecutorComponent(String beanName, ThreadPoolTaskExecutor executor, SpringTuneProperties properties,
            TimerReader timers) {
        this.beanName = beanName;
        this.executor = executor;
        this.properties = properties;
        this.timers = timers;
        this.samples = new SampleWindow<>(properties.getExecutor().getWindow().plus(properties.getSampling().getInterval()));
    }

    /**
     * The knob apply mode may change, or {@code null} until the executor is initialized: {@code corePoolSize}
     * with an unbounded queue (the pool never grows past it), otherwise {@code maxPoolSize}. The first call
     * installs a {@link RejectionCounter} (apply mode only), keeping the original rejection policy.
     */
    synchronized WritableKnob writableKnob() {
        if (writableKnob == null) {
            ThreadPoolExecutor pool = poolOrNull();
            if (pool == null) {
                return null;
            }
            rejections = RejectionCounter.install(pool);
            boolean unbounded = queueCapacity() >= Integer.MAX_VALUE;
            String setting = unbounded ? ExecutorPoolWriter.CORE : ExecutorPoolWriter.MAX;
            long configured = unbounded ? executor.getCorePoolSize() : executor.getMaxPoolSize();
            writableKnob = new WritableKnob(knobPrefix() + "." + setting, id(), WritableKnob.Kind.EXECUTOR, configured,
                    new ExecutorPoolWriter(executor, setting), this::counters);
        }
        return writableKnob;
    }

    /** Cumulative counters of this executor (verification signal R6) plus HTTP totals as evidence. */
    Map<String, Double> counters() {
        ThreadPoolExecutor pool = poolOrNull();
        RejectionCounter counter = rejections;
        Map<String, Double> counters = new LinkedHashMap<>();
        if (pool == null || counter == null) {
            return counters;
        }
        counters.put(Verification.COMPLETED, (double) pool.getCompletedTaskCount());
        counters.put(Verification.REJECTED, (double) counter.rejected());
        timers.http().ifPresent(http -> {
            counters.put(Verification.HTTP_REQUESTS, (double) http.requests());
            counters.put(Verification.HTTP_MS, http.totalMs());
            counters.put(Verification.HTTP_5XX, (double) http.serverErrors());
        });
        return counters;
    }

    @Override
    public Optional<ApplyPlan> applyPlan(Recommendation recommendation) {
        String prefix = knobPrefix() + ".";
        if (!recommendation.knobId().startsWith(prefix)) {
            return Optional.empty();
        }
        return ApplyPlans.executor(recommendation, beanName, recommendation.knobId().substring(prefix.length()),
                executor.getCorePoolSize(), executor.getMaxPoolSize(), queueCapacity() >= Integer.MAX_VALUE);
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
