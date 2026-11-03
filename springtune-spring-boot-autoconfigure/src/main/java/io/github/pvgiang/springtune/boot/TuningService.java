package io.github.pvgiang.springtune.boot;

import io.github.pvgiang.springtune.model.ConnectionBudget;
import io.github.pvgiang.springtune.model.Recommendation;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ClassUtils;

/**
 * Discovers tunable components, samples them and computes recommendations. Read-only by design: no code
 * path in this class or its components calls a setter on a pool or executor.
 */
public class TuningService {

    private static final Log LOG = LogFactory.getLog(TuningService.class);

    private final SpringTuneProperties properties;
    private final ListableBeanFactory beanFactory;
    private final Clock clock;
    private final Optional<Integer> budgetCap;
    private final boolean hikariPresent;
    private final boolean micrometerPresent;
    private volatile List<TunedComponent> components;
    private volatile List<Recommendation> recommendations = List.of();

    public TuningService(SpringTuneProperties properties, ListableBeanFactory beanFactory, Clock clock) {
        this.properties = properties;
        this.beanFactory = beanFactory;
        this.clock = clock;
        this.budgetCap = budgetCap(properties.getBudget());
        ClassLoader classLoader = beanFactory instanceof org.springframework.beans.factory.config.ConfigurableBeanFactory c
                && c.getBeanClassLoader() != null ? c.getBeanClassLoader() : TuningService.class.getClassLoader();
        this.hikariPresent = ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource", classLoader);
        this.micrometerPresent = ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", classLoader);
        if (properties.getSampling().getInterval().isNegative() || properties.getSampling().getInterval().isZero()) {
            throw new IllegalArgumentException("springtune.sampling.interval must be positive");
        }
    }

    static Optional<Integer> budgetCap(SpringTuneProperties.Budget budget) {
        Integer max = budget.getDatabaseMaxConnections();
        Integer replicas = budget.getMaxReplicas();
        if (max == null && replicas == null) {
            return Optional.empty();
        }
        if (max == null || replicas == null) {
            throw new IllegalArgumentException("springtune.budget.database-max-connections and "
                    + "springtune.budget.max-replicas must be set together");
        }
        return Optional.of(new ConnectionBudget(max, budget.getSafetyFactor()).perInstanceCap(replicas));
    }

    /** Components discovered on first use (after the context has created its singletons). */
    public List<TunedComponent> components() {
        List<TunedComponent> current = components;
        if (current == null) {
            synchronized (this) {
                if (components == null) {
                    components = List.copyOf(discover());
                }
                current = components;
            }
        }
        return current;
    }

    private List<TunedComponent> discover() {
        List<TunedComponent> found = new ArrayList<>();
        if (hikariPresent) {
            TimerReader timers = micrometerPresent ? MicrometerSupport.timerReader(beanFactory) : TimerReader.NONE;
            for (DataSource dataSource : beanFactory.getBeansOfType(DataSource.class, false, false).values()) {
                unwrapHikari(dataSource).ifPresent(hikari -> found.add(
                        new HikariComponent(hikari, timers, properties, budgetCap)));
            }
        }
        beanFactory.getBeansOfType(ThreadPoolTaskExecutor.class, false, false)
                .forEach((name, executor) -> found.add(new ExecutorComponent(name, executor, properties)));
        found.sort(Comparator.comparing(TunedComponent::id));
        LOG.info("SpringTune " + properties.getMode().name().toLowerCase() + " mode: watching "
                + found.stream().map(TunedComponent::id).toList() + " (read-only)");
        return found;
    }

    private static Optional<com.zaxxer.hikari.HikariDataSource> unwrapHikari(DataSource dataSource) {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            return Optional.of(hikari);
        }
        try {
            if (dataSource.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)) {
                return Optional.of(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class));
            }
        } catch (SQLException ex) {
            LOG.debug("Cannot unwrap " + dataSource, ex);
        }
        return Optional.empty();
    }

    /** Takes one sample of every component and, in ADVISE mode, recomputes recommendations. */
    public void sample() {
        Instant now = clock.instant();
        for (TunedComponent component : components()) {
            try {
                component.sample(now);
            } catch (RuntimeException ex) {
                LOG.warn("SpringTune could not sample " + component.id(), ex);
            }
        }
        if (properties.getMode() == SpringTuneProperties.Mode.ADVISE) {
            List<Recommendation> next = new ArrayList<>();
            for (TunedComponent component : components()) {
                try {
                    next.addAll(component.recommend(now));
                } catch (RuntimeException ex) {
                    LOG.warn("SpringTune could not evaluate " + component.id(), ex);
                }
            }
            recommendations = List.copyOf(next);
        }
    }

    public List<Recommendation> recommendations() {
        return recommendations;
    }

    public TuningSnapshot snapshot() {
        List<TuningSnapshot.Component> views = new ArrayList<>();
        for (TunedComponent component : components()) {
            views.add(new TuningSnapshot.Component(component.id(), component.sampleCount(), component.knobs(),
                    Map.copyOf(component.signals())));
        }
        SpringTuneProperties.Budget budget = properties.getBudget();
        return new TuningSnapshot(properties.getMode().name(), false, clock.instant(),
                new TuningSnapshot.Budget(budget.getDatabaseMaxConnections(), budget.getMaxReplicas(),
                        budget.getSafetyFactor(), budgetCap.orElse(null)),
                views, recommendations);
    }
}
