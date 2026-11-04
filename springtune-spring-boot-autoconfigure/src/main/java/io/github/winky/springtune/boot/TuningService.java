package io.github.winky.springtune.boot;

import io.github.winky.springtune.model.ConnectionBudget;
import io.github.winky.springtune.model.Recommendation;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ClassUtils;

/**
 * Discovers tunable components, samples them and computes recommendations. Read-only by design: no code
 * path in this class or its components calls a setter on a pool or executor. In apply mode the
 * {@link ChangeController} attached to it is driven by the same sampler tick and writes through
 * {@link KnobWriter}s only.
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
    private volatile ChangeController changes;

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

    Optional<Integer> budgetCap() {
        return budgetCap;
    }

    Clock clock() {
        return clock;
    }

    /** Apply mode only: the controller ticked after every sample and shown in the snapshot. */
    void attach(ChangeController controller) {
        this.changes = controller;
    }

    ChangeController changes() {
        return changes;
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
        TimerReader timers = micrometerPresent ? MicrometerSupport.timerReader(beanFactory) : TimerReader.NONE;
        if (hikariPresent) {
            for (Map.Entry<String, DataSource> entry : beanFactory.getBeansOfType(DataSource.class, false, false)
                    .entrySet()) {
                unwrapHikari(entry.getValue()).ifPresent(hikari -> found.add(
                        new HikariComponent(entry.getKey(), hikari, timers, properties, budgetCap)));
            }
        }
        beanFactory.getBeansOfType(ThreadPoolTaskExecutor.class, false, false)
                .forEach((name, executor) -> found.add(new ExecutorComponent(name, executor, properties, timers)));
        found.sort(Comparator.comparing(TunedComponent::id));
        LOG.info("SpringTune " + properties.getMode().name().toLowerCase() + " mode: watching "
                + found.stream().map(TunedComponent::id).toList()
                + (properties.getMode() == SpringTuneProperties.Mode.APPLY
                        ? " (apply mode, experimental: changes only through the guarded endpoint)" : " (read-only)"));
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

    /** Knobs apply mode may change (only components that have started). */
    List<WritableKnob> writableKnobs() {
        List<WritableKnob> knobs = new ArrayList<>();
        for (TunedComponent component : components()) {
            WritableKnob knob = null;
            if (hikariPresent && component instanceof HikariComponent hikari) {
                knob = hikari.writableKnob();
            } else if (component instanceof ExecutorComponent executor) {
                knob = executor.writableKnob();
            }
            if (knob != null) {
                knobs.add(knob);
            }
        }
        return knobs;
    }

    /**
     * Takes one sample of every component and, in ADVISE/APPLY mode, recomputes recommendations. In apply mode
     * the change controller then records counters and drives the change in flight.
     */
    public void sample() {
        Instant now = clock.instant();
        for (TunedComponent component : components()) {
            try {
                component.sample(now);
            } catch (RuntimeException ex) {
                LOG.warn("SpringTune could not sample " + component.id(), ex);
            }
        }
        if (properties.getMode() != SpringTuneProperties.Mode.OBSERVE) {
            recommendations = evaluate(now);
        }
        ChangeController controller = changes;
        if (controller != null) {
            controller.tick(now);
        }
    }

    /** Recomputes recommendations from the retained samples (also used by apply mode to re-check a request). */
    List<Recommendation> evaluate(Instant now) {
        List<Recommendation> next = new ArrayList<>();
        for (TunedComponent component : components()) {
            try {
                next.addAll(component.recommend(now));
            } catch (RuntimeException ex) {
                LOG.warn("SpringTune could not evaluate " + component.id(), ex);
            }
        }
        return List.copyOf(next);
    }

    public List<Recommendation> recommendations() {
        return recommendations;
    }

    /** Apply plans for the current INCREASE/DECREASE recommendations. */
    public List<ApplyPlan> applyPlans() {
        List<ApplyPlan> plans = new ArrayList<>();
        for (Recommendation rec : recommendations) {
            components().stream()
                    .map(component -> safePlan(component, rec))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(plans::add);
        }
        return plans;
    }

    private static ApplyPlan safePlan(TunedComponent component, Recommendation rec) {
        try {
            return component.applyPlan(rec).orElse(null);
        } catch (RuntimeException ex) {
            LOG.debug("SpringTune could not plan " + rec.knobId(), ex);
            return null;
        }
    }

    public TuningSnapshot snapshot() {
        List<TuningSnapshot.Component> views = new ArrayList<>();
        for (TunedComponent component : components()) {
            views.add(new TuningSnapshot.Component(component.id(), component.sampleCount(), component.knobs(),
                    Map.copyOf(component.signals())));
        }
        SpringTuneProperties.Budget budget = properties.getBudget();
        ChangeController controller = changes;
        boolean apply = properties.getMode() == SpringTuneProperties.Mode.APPLY && controller != null;
        return new TuningSnapshot(properties.getMode().name(), apply, clock.instant(),
                new TuningSnapshot.Budget(budget.getDatabaseMaxConnections(), budget.getMaxReplicas(),
                        budget.getSafetyFactor(), budgetCap.orElse(null)),
                views, recommendations, applyPlans(), apply ? controller.view() : null);
    }
}
