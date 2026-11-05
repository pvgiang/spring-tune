package io.github.winkytek.springtune.boot;

import com.zaxxer.hikari.HikariConfigMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Writes a Hikari pool's {@code maximumPoolSize} through {@link HikariConfigMXBean} (the runtime-changeable
 * view of a started pool).
 *
 * <p>{@code minimumIdle} moves with it when it equals the maximum (a fixed-size pool, Hikari's default):
 * raising only the maximum would silently turn a fixed pool into a dynamic one. It is also lowered when the
 * new maximum is below it. Order: when growing, the maximum first, then {@code minimumIdle}; when shrinking,
 * {@code minimumIdle} first, then the maximum, so {@code minimumIdle <= maximumPoolSize} holds at every step.
 *
 * <p>Hikari applies a larger maximum on demand (new connections are added as threads wait). A smaller
 * maximum is applied lazily: connections above it are retired as they idle out or reach {@code maxLifetime},
 * not closed immediately.
 */
final class HikariPoolWriter implements KnobWriter {

    static final String MAX = "maximumPoolSize";
    static final String MIN_IDLE = "minimumIdle";

    private final Supplier<HikariConfigMXBean> config;

    HikariPoolWriter(Supplier<HikariConfigMXBean> config) {
        this.config = config;
    }

    @Override
    public String setting() {
        return MAX;
    }

    @Override
    public Map<String, Long> read() {
        HikariConfigMXBean bean = config.get();
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(MAX, (long) bean.getMaximumPoolSize());
        values.put(MIN_IDLE, (long) bean.getMinimumIdle());
        return values;
    }

    @Override
    public Map<String, Long> plan(long target) {
        HikariConfigMXBean bean = config.get();
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(MAX, target);
        int minIdle = bean.getMinimumIdle();
        if (minIdle == bean.getMaximumPoolSize() || minIdle > target) {
            values.put(MIN_IDLE, target);
        }
        return values;
    }

    @Override
    public void write(Map<String, Long> values) {
        HikariConfigMXBean bean = config.get();
        int max = Math.toIntExact(values.get(MAX));
        Long minIdle = values.get(MIN_IDLE);
        if (max >= bean.getMaximumPoolSize()) {
            bean.setMaximumPoolSize(max);
            if (minIdle != null) {
                bean.setMinimumIdle(Math.toIntExact(minIdle));
            }
        } else {
            if (minIdle != null) {
                bean.setMinimumIdle(Math.toIntExact(minIdle));
            }
            bean.setMaximumPoolSize(max);
        }
    }
}
