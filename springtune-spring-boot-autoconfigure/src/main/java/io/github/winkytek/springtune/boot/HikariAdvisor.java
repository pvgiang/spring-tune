package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recommendation rules for a HikariCP pool. Pure functions over samples so they can be verified with
 * synthetic signals.
 *
 * <ul>
 *   <li><b>ST-REC-HIKARI-001</b>: threads wait for connections in at least {@code saturatedRatio} of the
 *       window while connections are returned quickly: the pool is the bottleneck. INCREASE by
 *       {@code step}, capped by the connection budget (HOLD when the cap is reached).</li>
 *   <li><b>ST-REC-HIKARI-002</b>: threads wait, but connections are held longer than {@code slowUsage}: the
 *       database work is the bottleneck. HOLD: more connections would only add load to the database.
 *       Also HOLD (low confidence) when hold-time metrics are unavailable.</li>
 *   <li><b>ST-REC-HIKARI-004</b>: threads wait, but the pool holds fewer connections than its maximum in at
 *       least {@code STARVED_RATIO} of the window's samples, or acquire timeouts occurred while it did: the
 *       database refuses or fails new connections (server or role connection limit, network, authentication).
 *       HOLD: a larger pool cannot open connections the current one cannot, and would add load to a failing
 *       database. Checked before the other saturation rules.</li>
 *   <li><b>ST-REC-HIKARI-003</b>: over {@code lowUtilizationWindow} no thread ever waited and peak active
 *       stayed below {@code lowUtilization} of the pool: DECREASE to twice the peak (min 2), freeing
 *       database connections for other services.</li>
 * </ul>
 */
final class HikariAdvisor {

    static final String RAISE = "ST-REC-HIKARI-001";
    static final String HOLD_SLOW = "ST-REC-HIKARI-002";
    static final String LOWER = "ST-REC-HIKARI-003";
    static final String HOLD_STARVED = "ST-REC-HIKARI-004";

    /** Share of window samples in which threads waited while the pool was below its maximum. */
    static final double STARVED_RATIO = 0.5;

    /** Micrometer timers rule 001 needs to tell a pool that is too small from slow database work. */
    static final String ACQUIRE_TIMER = "hikaricp.connections.acquire";
    static final String USAGE_TIMER = "hikaricp.connections.usage";
    static final List<String> HOLD_TIME_METERS = List.of(ACQUIRE_TIMER, USAGE_TIMER);

    /** Rules decided over the saturation window, in the order they are reported. */
    private static final List<String> SATURATION_RULES = List.of(RAISE, HOLD_SLOW, HOLD_STARVED);

    /** Every rule, in the order statuses are reported. */
    static final List<String> RULES = List.of(RAISE, HOLD_SLOW, HOLD_STARVED, LOWER);

    private HikariAdvisor() {
    }

    /**
     * @param knobId    id of the maximumPoolSize knob
     * @param current   current maximumPoolSize
     * @param window    samples covering the saturation window (empty if not yet covered)
     * @param longWindow samples covering the low-utilization window (empty if not yet covered)
     * @param budgetCap per-instance connection budget cap, or empty when not configured
     */
    static Optional<Recommendation> advise(String knobId, int current, List<HikariSample> window,
            List<HikariSample> longWindow, Optional<Integer> budgetCap, SpringTuneProperties.Hikari settings,
            Instant now) {
        return evaluate("hikari", knobId, current, SampleWindow.Coverage.of(window, settings.getWindow()),
                SampleWindow.Coverage.of(longWindow, settings.getLowUtilizationWindow()), budgetCap, settings, now,
                HOLD_TIME_METERS).recommendations().stream().findFirst();
    }

    /**
     * Evaluates every rule for one pool in one pass: the recommendation (at most one) and each rule's status come
     * from the same checks. Rule 001 is {@code NO_SIGNAL} whenever a covered window has samples without hold-time
     * timers: it can never fire then (waiting threads get the low-confidence 002 HOLD instead).
     *
     * @param missingMeters which of {@link #HOLD_TIME_METERS} the reader lacks now, named in a NO_SIGNAL status
     *                      (both when empty, e.g. when the meters appeared after the window began)
     */
    static TunedComponent.Evaluation evaluate(String componentId, String knobId, int current,
            SampleWindow.Coverage<HikariSample> window, SampleWindow.Coverage<HikariSample> longWindow,
            Optional<Integer> budgetCap, SpringTuneProperties.Hikari settings, Instant now, List<String> missingMeters) {
        Map<String, RuleEvaluation> rules = new LinkedHashMap<>();
        List<String> missing = missingMeters.isEmpty() ? HOLD_TIME_METERS : missingMeters;
        Recommendation rec = null;
        if (!window.covered()) {
            for (String rule : SATURATION_RULES) {
                rules.put(rule, RuleEvaluation.collecting(componentId, rule, window));
            }
        } else {
            List<HikariSample> samples = window.samples();
            boolean timers = samples.stream().allMatch(HikariSample::timersAvailable);
            double waiting = SampleWindow.ratio(samples, s -> s.pending() > 0);
            if (waiting >= settings.getSaturatedRatio()) {
                rec = saturated(knobId, current, samples, waiting, budgetCap, settings, now);
            }
            String fired = rec == null ? null : rec.ruleId();
            for (String rule : SATURATION_RULES) {
                if (rule.equals(fired)) {
                    rules.put(rule, RuleEvaluation.recommending(componentId, rule));
                } else if (rule.equals(RAISE) && !timers) {
                    rules.put(rule, RuleEvaluation.noSignal(componentId, rule, missing));
                } else {
                    rules.put(rule, RuleEvaluation.quiet(componentId, rule, fired));
                }
            }
        }
        if (!longWindow.covered()) {
            rules.put(LOWER, RuleEvaluation.collecting(componentId, LOWER, longWindow));
        } else if (rec != null) {
            rules.put(LOWER, RuleEvaluation.quiet(componentId, LOWER, rec.ruleId()));
        } else {
            rec = lower(knobId, current, longWindow.samples(), settings, now);
            rules.put(LOWER, rec != null ? RuleEvaluation.recommending(componentId, LOWER)
                    : RuleEvaluation.quiet(componentId, LOWER, null));
        }
        List<RuleEvaluation> ordered = RULES.stream().map(rules::get).toList();
        return new TunedComponent.Evaluation(ordered, rec == null ? List.of() : List.of(rec));
    }

    private static Recommendation lower(String knobId, int current, List<HikariSample> longWindow,
            SpringTuneProperties.Hikari settings, Instant now) {
        if (longWindow.stream().allMatch(s -> s.pending() == 0)) {
            int peak = longWindow.stream().mapToInt(HikariSample::active).max().orElse(0);
            if ((double) peak / current < settings.getLowUtilization()) {
                int proposed = Math.max(2, peak * 2);
                if (proposed < current) {
                    Map<String, String> evidence = new LinkedHashMap<>();
                    evidence.put("window", Format.duration(settings.getLowUtilizationWindow()));
                    evidence.put("samples", String.valueOf(longWindow.size()));
                    evidence.put("peakActiveConnections", peak + " of " + current);
                    evidence.put("threadsEverWaiting", "no");
                    return new Recommendation(LOWER, knobId, Action.DECREASE, current, proposed,
                            "Over the last " + Format.duration(settings.getLowUtilizationWindow()) + " at most " + peak
                                    + " of " + current + " connections were in use and no thread ever waited."
                                    + " Lowering the pool to " + proposed + " frees " + (current - proposed)
                                    + " database connections per instance for other services.",
                            evidence, Confidence.MEDIUM, List.of("ST-HIKARI-004"), now);
                }
            }
        }
        return null;
    }

    static Recommendation saturated(String knobId, int current, List<HikariSample> window, double waiting,
            Optional<Integer> budgetCap, SpringTuneProperties.Hikari settings, Instant now) {
        HikariSample first = window.get(0);
        HikariSample last = window.get(window.size() - 1);
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("window", Format.duration(settings.getWindow()));
        evidence.put("samples", String.valueOf(window.size()));
        evidence.put("samplesWithWaitingThreads", Format.percent(waiting));
        evidence.put("maxWaitingThreads", String.valueOf(window.stream().mapToInt(HikariSample::pending).max().orElse(0)));

        double starved = SampleWindow.ratio(window, HikariSample::starved);
        double timeouts = last.timeouts() - first.timeouts();
        if (starved >= STARVED_RATIO || (timeouts > 0 && starved > 0)) {
            int fewest = window.stream().filter(HikariSample::starved).mapToInt(HikariSample::total).min()
                    .orElse(current);
            evidence.put("samplesBelowMaximumWhileWaiting", Format.percent(starved));
            evidence.put("fewestOpenConnectionsWhileWaiting", fewest + " of " + current);
            evidence.put("acquireTimeouts", String.valueOf(Math.round(timeouts)));
            return new Recommendation(HOLD_STARVED, knobId, Action.HOLD, current, current,
                    "Do not raise the pool. Threads wait for connections, but the pool could not open the"
                            + " connections it already has room for: it held fewer than " + current
                            + " connections (as few as " + fewest + ") while threads waited in "
                            + Format.percent(starved) + " of samples" + (timeouts > 0 ? ", and " + Math.round(timeouts)
                            + " connection requests timed out" : "") + ". The database is refusing or failing new"
                            + " connections (max_connections, a role CONNECTION LIMIT, network or authentication)."
                            + " A larger pool would only add connection attempts to a failing database.",
                    evidence, Confidence.HIGH, List.of("ST-HIKARI-004"), now);
        }

        boolean timers = window.stream().allMatch(HikariSample::timersAvailable);
        long usageCount = last.usageCount() - first.usageCount();
        long acquireCount = last.acquireCount() - first.acquireCount();
        if (!timers || usageCount <= 0) {
            evidence.put("connectionHoldTime", "unavailable");
            return new Recommendation(HOLD_SLOW, knobId, Action.HOLD, current, current,
                    "Threads wait for connections, but connection hold times are not available, so a pool that is"
                            + " too small cannot be told apart from slow queries. Enable HikariCP Micrometer metrics"
                            + " (spring-boot-starter-actuator registers them) before changing the pool.",
                    evidence, Confidence.LOW, List.of("ST-TX-001", "ST-HIKARI-002"), now);
        }
        double usageMean = (last.usageTotalMs() - first.usageTotalMs()) / usageCount;
        double acquireMean = acquireCount > 0 ? (last.acquireTotalMs() - first.acquireTotalMs()) / acquireCount : 0;
        evidence.put("meanConnectionHoldTime", Format.millis(usageMean));
        evidence.put("meanAcquireTime", Format.millis(acquireMean));
        evidence.put("slowHoldThreshold", Format.millis(settings.getSlowUsage().toMillis()));

        if (usageMean > settings.getSlowUsage().toMillis()) {
            return new Recommendation(HOLD_SLOW, knobId, Action.HOLD, current, current,
                    "Do not raise the pool. Threads wait for connections because each connection is held for "
                            + Format.millis(usageMean) + " on average (threshold "
                            + Format.millis(settings.getSlowUsage().toMillis()) + "). The database work is the"
                            + " bottleneck, and more connections would add load to an already busy database. Look"
                            + " for slow queries, long transactions or remote calls made inside transactions.",
                    evidence, Confidence.HIGH, List.of("ST-TX-001", "ST-HIKARI-002"), now);
        }

        int proposed = Format.step(current, settings.getStep());
        evidence.put("budgetCap", budgetCap.map(String::valueOf).orElse("not configured"));
        if (budgetCap.isPresent() && proposed > budgetCap.get()) {
            proposed = budgetCap.get();
        }
        if (proposed <= current) {
            int cap = budgetCap.orElse(current);
            String position = current > cap
                    ? "already above its connection budget cap of " + cap + " (current " + current + "), so the fleet"
                            + " can exceed the database's max_connections at peak scale (ST-HIKARI-004)"
                    : "already at its connection budget cap of " + cap;
            return new Recommendation(RAISE, knobId, Action.HOLD, current, current,
                    "Do not raise the pool. It is the bottleneck (threads wait while connections are returned in "
                            + Format.millis(usageMean) + "), but it is " + position + ". Reduce per-request"
                            + " connection use, cap replicas, or raise the database's max_connections.",
                    evidence, Confidence.MEDIUM, List.of("ST-HIKARI-004"), now);
        }

        String budgetNote = budgetCap.isPresent()
                ? " This stays within the connection budget cap of " + budgetCap.get() + "."
                : " No connection budget is configured (springtune.budget.*): check max_connections against"
                        + " pool size × replicas before applying.";
        return new Recommendation(RAISE, knobId, Action.INCREASE, current, proposed,
                "Raise maximumPoolSize from " + current + " to " + proposed + ". Threads waited for a connection in "
                        + Format.percent(waiting) + " of samples over " + Format.duration(settings.getWindow())
                        + ", while connections were returned quickly (" + Format.millis(usageMean)
                        + " average hold), so the pool size is the bottleneck." + budgetNote,
                evidence, budgetCap.isPresent() ? Confidence.HIGH : Confidence.MEDIUM, List.of("ST-HIKARI-004"), now);
    }
}
