package io.github.winkytek.springtune.boot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Writes a {@link ThreadPoolTaskExecutor}'s {@code corePoolSize} or {@code maxPoolSize}; Spring passes both
 * through to the live {@code ThreadPoolExecutor}.
 *
 * <p>{@code ThreadPoolExecutor} rejects a core size above the maximum (and a maximum below the core size), so
 * the order matters. Growing: the maximum first, then the core size. Shrinking: the core size first, then the
 * maximum. When the target would cross the other bound, that bound moves along (core above max raises max;
 * max below core lowers core).
 *
 * <p>With an unbounded queue the pool never grows past its core size, so only {@code corePoolSize} is
 * offered as a writable knob for such executors (see {@link ExecutorComponent}).
 */
final class ExecutorPoolWriter implements KnobWriter {

    static final String CORE = "corePoolSize";
    static final String MAX = "maxPoolSize";

    private final ThreadPoolTaskExecutor executor;
    private final String setting;

    ExecutorPoolWriter(ThreadPoolTaskExecutor executor, String setting) {
        if (!CORE.equals(setting) && !MAX.equals(setting)) {
            throw new IllegalArgumentException("Unsupported executor setting " + setting);
        }
        this.executor = executor;
        this.setting = setting;
    }

    @Override
    public String setting() {
        return setting;
    }

    @Override
    public Map<String, Long> read() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(setting, (long) (CORE.equals(setting) ? executor.getCorePoolSize() : executor.getMaxPoolSize()));
        values.put(other(), (long) (CORE.equals(setting) ? executor.getMaxPoolSize() : executor.getCorePoolSize()));
        return values;
    }

    private String other() {
        return CORE.equals(setting) ? MAX : CORE;
    }

    @Override
    public Map<String, Long> plan(long target) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(setting, target);
        if (CORE.equals(setting) && target > executor.getMaxPoolSize()) {
            values.put(MAX, target);
        }
        if (MAX.equals(setting) && target < executor.getCorePoolSize()) {
            values.put(CORE, target);
        }
        return values;
    }

    @Override
    public void write(Map<String, Long> values) {
        Long core = values.get(CORE);
        Long max = values.get(MAX);
        boolean growing = max != null ? max >= executor.getMaxPoolSize() : core >= executor.getCorePoolSize();
        if (growing) {
            if (max != null) {
                executor.setMaxPoolSize(Math.toIntExact(max));
            }
            if (core != null) {
                executor.setCorePoolSize(Math.toIntExact(core));
            }
        } else {
            if (core != null) {
                executor.setCorePoolSize(Math.toIntExact(core));
            }
            if (max != null) {
                executor.setMaxPoolSize(Math.toIntExact(max));
            }
        }
    }
}
