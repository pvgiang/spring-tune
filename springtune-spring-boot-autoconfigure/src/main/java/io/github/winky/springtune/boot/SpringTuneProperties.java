package io.github.winky.springtune.boot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code springtune.*} runtime settings. Nothing here can change the application: this release only
 * observes and advises.
 */
@ConfigurationProperties("springtune")
public class SpringTuneProperties {

    /** Whether the SpringTune starter is active. */
    private boolean enabled = true;

    /** OBSERVE: knobs and signals only. ADVISE: also compute recommendations. */
    private Mode mode = Mode.ADVISE;

    private final Sampling sampling = new Sampling();

    private final Budget budget = new Budget();

    private final Hikari hikari = new Hikari();

    private final Executor executor = new Executor();

    public enum Mode {
        OBSERVE,
        ADVISE
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Sampling getSampling() {
        return sampling;
    }

    public Budget getBudget() {
        return budget;
    }

    public Hikari getHikari() {
        return hikari;
    }

    public Executor getExecutor() {
        return executor;
    }

    public static class Sampling {

        /** How often signals are sampled. */
        private Duration interval = Duration.ofSeconds(10);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    /**
     * Connection budget for this service, same formula as rule ST-HIKARI-004:
     * per-instance cap = floor(database-max-connections × safety-factor / max-replicas).
     */
    public static class Budget {

        /** The database's max_connections. Unset means recommendations are not budget-capped. */
        private Integer databaseMaxConnections;

        /** Maximum instances of this service sharing the budget. */
        private Integer maxReplicas;

        /** Share of max_connections pools may use. */
        private double safetyFactor = 0.8;

        public Integer getDatabaseMaxConnections() {
            return databaseMaxConnections;
        }

        public void setDatabaseMaxConnections(Integer databaseMaxConnections) {
            this.databaseMaxConnections = databaseMaxConnections;
        }

        public Integer getMaxReplicas() {
            return maxReplicas;
        }

        public void setMaxReplicas(Integer maxReplicas) {
            this.maxReplicas = maxReplicas;
        }

        public double getSafetyFactor() {
            return safetyFactor;
        }

        public void setSafetyFactor(double safetyFactor) {
            this.safetyFactor = safetyFactor;
        }
    }

    public static class Hikari {

        /** Window over which pool saturation is judged. */
        private Duration window = Duration.ofMinutes(5);

        /** Fraction of samples in the window with waiting threads that counts as sustained saturation. */
        private double saturatedRatio = 0.8;

        /** Mean connection hold time above which raising the pool is advised against (slow queries/transactions). */
        private Duration slowUsage = Duration.ofMillis(500);

        /** Increase step as a fraction of the current size (at least +1). */
        private double step = 0.25;

        /** Window over which low utilization is judged before suggesting a smaller pool. */
        private Duration lowUtilizationWindow = Duration.ofHours(24);

        /** Peak active / maximum pool size below which the pool counts as oversized. */
        private double lowUtilization = 0.2;

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public double getSaturatedRatio() {
            return saturatedRatio;
        }

        public void setSaturatedRatio(double saturatedRatio) {
            this.saturatedRatio = saturatedRatio;
        }

        public Duration getSlowUsage() {
            return slowUsage;
        }

        public void setSlowUsage(Duration slowUsage) {
            this.slowUsage = slowUsage;
        }

        public double getStep() {
            return step;
        }

        public void setStep(double step) {
            this.step = step;
        }

        public Duration getLowUtilizationWindow() {
            return lowUtilizationWindow;
        }

        public void setLowUtilizationWindow(Duration lowUtilizationWindow) {
            this.lowUtilizationWindow = lowUtilizationWindow;
        }

        public double getLowUtilization() {
            return lowUtilization;
        }

        public void setLowUtilization(double lowUtilization) {
            this.lowUtilization = lowUtilization;
        }
    }

    public static class Executor {

        /** Window over which executor backlog is judged. */
        private Duration window = Duration.ofMinutes(5);

        /** Fraction of samples with queued tasks and all threads busy that counts as sustained backlog. */
        private double backlogRatio = 0.8;

        /** Increase step as a fraction of the current size (at least +1). */
        private double step = 0.25;

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public double getBacklogRatio() {
            return backlogRatio;
        }

        public void setBacklogRatio(double backlogRatio) {
            this.backlogRatio = backlogRatio;
        }

        public double getStep() {
            return step;
        }

        public void setStep(double step) {
            this.step = step;
        }
    }
}
