package io.github.winky.springtune.boot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code springtune.*} runtime settings. In {@code observe} and {@code advise} mode nothing can change the
 * application. {@code apply} mode (experimental, trust ladder level 3) lets an authenticated operator apply one
 * current recommendation at a time, within the guardrails of {@link Apply}.
 */
@ConfigurationProperties("springtune")
public class SpringTuneProperties {

    /** Whether the SpringTune starter is active. */
    private boolean enabled = true;

    /**
     * OBSERVE: knobs and signals only. ADVISE: also compute recommendations and apply plans. APPLY
     * (experimental): ADVISE plus a guarded, verified, auto-reverted write operation; requires Spring Security.
     */
    private Mode mode = Mode.ADVISE;

    private final Sampling sampling = new Sampling();

    private final Budget budget = new Budget();

    private final Hikari hikari = new Hikari();

    private final Executor executor = new Executor();

    private final Apply apply = new Apply();

    public enum Mode {
        OBSERVE,
        ADVISE,
        /** Experimental: apply one recommendation at a time through the endpoint, verified and auto-reverted. */
        APPLY
    }

    public Apply getApply() {
        return apply;
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

    /**
     * Guardrails for {@code springtune.mode=apply}. Every change is ephemeral (lost on restart), bounded,
     * verified against the knob's own signals and reverted when it regresses or cannot be verified.
     */
    public static class Apply {

        /** Static kill switch: false refuses every apply (revert and freeze still work). */
        private boolean enabled = true;

        /** Role the caller must have (checked with the endpoint's SecurityContext). Unset: any authenticated caller. */
        private String requiredRole;

        /** Largest step as a fraction of the current value: |new - current| <= max(1, ceil(current x max-step)). */
        private double maxStep = 0.5;

        /** Applied values stay within [startup / factor, startup x factor] of the value configured at startup. */
        private double maxGrowthFactor = 2.0;

        /** Window before the change that verification compares against. */
        private Duration baseline = Duration.ofMinutes(5);

        /** Window after the warmup over which the change is verified. */
        private Duration verificationWindow = Duration.ofMinutes(5);

        /** Time after the change that verification ignores (pool growth, connection setup). */
        private Duration warmup = Duration.ofSeconds(30);

        /** What to do when a change cannot be verified (too little traffic, throughput shifted more than 2x). */
        private OnInconclusive onInconclusive = OnInconclusive.REVERT;

        /** Lock on a knob after a confirmed (or superseded) change. */
        private Duration cooldown = Duration.ofMinutes(15);

        /** Lock on a knob after a revert. */
        private Duration cooldownAfterRevert = Duration.ofMinutes(60);

        /** Number of audit entries kept in memory and shown by the endpoint. */
        private int auditSize = 100;

        public enum OnInconclusive {
            REVERT,
            KEEP
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole;
        }

        public double getMaxStep() {
            return maxStep;
        }

        public void setMaxStep(double maxStep) {
            this.maxStep = maxStep;
        }

        public double getMaxGrowthFactor() {
            return maxGrowthFactor;
        }

        public void setMaxGrowthFactor(double maxGrowthFactor) {
            this.maxGrowthFactor = maxGrowthFactor;
        }

        public Duration getBaseline() {
            return baseline;
        }

        public void setBaseline(Duration baseline) {
            this.baseline = baseline;
        }

        public Duration getVerificationWindow() {
            return verificationWindow;
        }

        public void setVerificationWindow(Duration verificationWindow) {
            this.verificationWindow = verificationWindow;
        }

        public Duration getWarmup() {
            return warmup;
        }

        public void setWarmup(Duration warmup) {
            this.warmup = warmup;
        }

        public OnInconclusive getOnInconclusive() {
            return onInconclusive;
        }

        public void setOnInconclusive(OnInconclusive onInconclusive) {
            this.onInconclusive = onInconclusive;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public Duration getCooldownAfterRevert() {
            return cooldownAfterRevert;
        }

        public void setCooldownAfterRevert(Duration cooldownAfterRevert) {
            this.cooldownAfterRevert = cooldownAfterRevert;
        }

        public int getAuditSize() {
            return auditSize;
        }

        public void setAuditSize(int auditSize) {
            this.auditSize = auditSize;
        }
    }
}
