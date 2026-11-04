package io.github.winky.springtune.boot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verification rules for an applied change. Pure functions over two windows of counter deltas:
 * <b>B</b> (baseline, {@code [t0 - baseline, t0]}) and <b>V</b> (verification, {@code [t0 + warmup, now]}).
 * Only the changed knob's <b>own</b> component is judged; HTTP latency and 5xx are recorded as evidence and
 * never trigger a revert (an unrelated downstream incident must not revert, or shrink, a pool mid-spike).
 *
 * <table>
 *   <caption>Rules</caption>
 *   <tr><th>Rule</th><th>Regression when</th><th>Counts as evaluated when</th></tr>
 *   <tr><td>R1 (Hikari)</td><td>timeouts per acquire attempt rose by more than 0.1 percentage points and V has
 *       at least 3 timeouts; checked on every tick (fast fail)</td><td>regressed, or at least 100 attempts in
 *       B and in V</td></tr>
 *   <tr><td>R2 (Hikari)</td><td>mean connection hold time in V &gt; 1.5 &times; B + 5 ms</td><td>at least 100
 *       usages in B and in V</td></tr>
 *   <tr><td>R3 (Hikari)</td><td>mean acquire time in V &gt; 1.5 &times; B + 5 ms</td><td>at least 100
 *       acquires in B and in V</td></tr>
 *   <tr><td>R6 (executor)</td><td>rejections per second in V higher than in B and V has at least 1
 *       rejection</td><td>regressed, or at least 100 completed tasks in B and in V</td></tr>
 * </table>
 *
 * <p>Throughput (Hikari: acquire attempts per second; executor: completed tasks per second) must be within
 * 2&times; between B and V, otherwise the windows are not comparable and the verdict is INCONCLUSIVE. When no
 * rule is evaluated the verdict is INCONCLUSIVE too. Otherwise a regression gives REGRESSED and none gives OK.
 */
final class Verification {

    static final String ACQUIRES = "acquires";
    static final String ACQUIRE_MS = "acquireMs";
    static final String USAGES = "usages";
    static final String USAGE_MS = "usageMs";
    static final String TIMEOUTS = "timeouts";
    static final String COMPLETED = "completed";
    static final String REJECTED = "rejected";
    static final String HTTP_REQUESTS = "httpRequests";
    static final String HTTP_MS = "httpMs";
    static final String HTTP_5XX = "http5xx";

    static final int MIN_EVENTS = 100;
    static final int MIN_TIMEOUTS = 3;
    static final double TIMEOUT_RATE_DELTA = 0.001;
    static final double MEAN_FACTOR = 1.5;
    static final double MEAN_SLACK_MS = 5;
    static final double THROUGHPUT_FACTOR = 2;

    private Verification() {
    }

    enum Verdict {
        OK,
        REGRESSED,
        INCONCLUSIVE
    }

    /** Cumulative counters read at one sampler tick. */
    record Counters(Instant at, Map<String, Double> values) implements SampleWindow.Timed {
        Counters {
            values = Map.copyOf(values);
        }
    }

    /** Counter deltas over a window of {@code seconds}. */
    record Window(double seconds, Map<String, Double> delta) {

        static Window between(Counters from, Counters to) {
            Map<String, Double> delta = new LinkedHashMap<>();
            to.values().forEach((key, value) -> {
                Double start = from.values().get(key);
                if (start != null) {
                    delta.put(key, value - start);
                }
            });
            return new Window(Duration.between(from.at(), to.at()).toMillis() / 1000.0, delta);
        }

        double get(String key) {
            return delta.getOrDefault(key, 0.0);
        }

        boolean has(String key) {
            return delta.containsKey(key);
        }

        double rate(double count) {
            return seconds > 0 ? count / seconds : 0;
        }
    }

    record Result(Verdict verdict, List<String> reasons, Map<String, String> evidence) {
    }

    private record Rule(String id, boolean evaluated, boolean regressed, String message) {
    }

    static Result verify(WritableKnob.Kind kind, Window b, Window v) {
        return kind == WritableKnob.Kind.HIKARI ? hikari(b, v) : executor(b, v);
    }

    /** R1 only, for the per-tick fast fail: a regression with comparable throughput. */
    static boolean fastFail(WritableKnob.Kind kind, Window b, Window v) {
        if (kind != WritableKnob.Kind.HIKARI) {
            return false;
        }
        Rule r1 = timeouts(b, v);
        return r1.regressed() && comparable(attempts(b), attempts(v), b, v);
    }

    private static double attempts(Window w) {
        return w.get(ACQUIRES) + w.get(TIMEOUTS);
    }

    private static Result hikari(Window b, Window v) {
        Map<String, String> evidence = new LinkedHashMap<>();
        List<Rule> rules = List.of(timeouts(b, v),
                mean("R2", "mean connection hold time", USAGES, USAGE_MS, b, v),
                mean("R3", "mean acquire time", ACQUIRES, ACQUIRE_MS, b, v));
        evidence.put("windows", "B " + seconds(b) + ", V " + seconds(v));
        evidence.put("acquireAttemptsPerSecond", rate(b.rate(attempts(b))) + " -> " + rate(v.rate(attempts(v))));
        evidence.put("timeoutsPerAttempt", percent(ratio(b.get(TIMEOUTS), attempts(b))) + " -> "
                + percent(ratio(v.get(TIMEOUTS), attempts(v))) + " (" + count(b.get(TIMEOUTS)) + " -> "
                + count(v.get(TIMEOUTS)) + " timeouts)");
        evidence.put("meanConnectionHoldTime", ms(ratio(b.get(USAGE_MS), b.get(USAGES))) + " -> "
                + ms(ratio(v.get(USAGE_MS), v.get(USAGES))) + " (" + count(b.get(USAGES)) + " -> "
                + count(v.get(USAGES)) + " usages)");
        evidence.put("meanAcquireTime", ms(ratio(b.get(ACQUIRE_MS), b.get(ACQUIRES))) + " -> "
                + ms(ratio(v.get(ACQUIRE_MS), v.get(ACQUIRES))) + " (" + count(b.get(ACQUIRES)) + " -> "
                + count(v.get(ACQUIRES)) + " acquires)");
        http(b, v, evidence);
        return decide(rules, attempts(b), attempts(v), b, v, "acquire attempts", evidence);
    }

    private static Result executor(Window b, Window v) {
        Map<String, String> evidence = new LinkedHashMap<>();
        double rateB = b.rate(b.get(REJECTED));
        double rateV = v.rate(v.get(REJECTED));
        boolean regressed = v.get(REJECTED) >= 1 && rateV > rateB;
        boolean evaluated = regressed || (b.get(COMPLETED) >= MIN_EVENTS && v.get(COMPLETED) >= MIN_EVENTS);
        String message = "R6 rejections per second " + rate(rateB) + " -> " + rate(rateV)
                + (regressed ? ": more tasks are rejected than before" : ": not higher than before");
        evidence.put("windows", "B " + seconds(b) + ", V " + seconds(v));
        evidence.put("completedTasksPerSecond", rate(b.rate(b.get(COMPLETED))) + " -> " + rate(v.rate(v.get(COMPLETED))));
        evidence.put("rejectedTasks", count(b.get(REJECTED)) + " -> " + count(v.get(REJECTED)));
        http(b, v, evidence);
        List<Rule> rules = List.of(new Rule("R6", evaluated, regressed,
                evaluated ? message : "R6 needs at least " + MIN_EVENTS + " completed tasks in B and V (had "
                        + count(b.get(COMPLETED)) + " and " + count(v.get(COMPLETED)) + ")"));
        return decide(rules, b.get(COMPLETED), v.get(COMPLETED), b, v, "completed tasks", evidence);
    }

    private static Rule timeouts(Window b, Window v) {
        double rateB = ratio(b.get(TIMEOUTS), attempts(b));
        double rateV = ratio(v.get(TIMEOUTS), attempts(v));
        boolean regressed = v.get(TIMEOUTS) >= MIN_TIMEOUTS && rateV - rateB > TIMEOUT_RATE_DELTA;
        boolean evaluated = regressed || (attempts(b) >= MIN_EVENTS && attempts(v) >= MIN_EVENTS);
        String message = evaluated
                ? "R1 timeouts per acquire attempt " + percent(rateB) + " -> " + percent(rateV)
                        + (regressed ? ": connection timeouts increased" : ": no increase")
                : "R1 needs at least " + MIN_EVENTS + " acquire attempts in B and V (had " + count(attempts(b))
                        + " and " + count(attempts(v)) + ")";
        return new Rule("R1", evaluated, regressed, message);
    }

    private static Rule mean(String id, String label, String countKey, String totalKey, Window b, Window v) {
        if (!b.has(countKey) || !v.has(countKey)) {
            return new Rule(id, false, false, id + " " + label + ": metrics unavailable");
        }
        if (b.get(countKey) < MIN_EVENTS || v.get(countKey) < MIN_EVENTS) {
            return new Rule(id, false, false, id + " needs at least " + MIN_EVENTS + " " + countKey + " in B and V (had "
                    + count(b.get(countKey)) + " and " + count(v.get(countKey)) + ")");
        }
        double meanB = b.get(totalKey) / b.get(countKey);
        double meanV = v.get(totalKey) / v.get(countKey);
        double limit = MEAN_FACTOR * meanB + MEAN_SLACK_MS;
        boolean regressed = meanV > limit;
        return new Rule(id, true, regressed, id + " " + label + " " + ms(meanB) + " -> " + ms(meanV) + " (limit "
                + ms(limit) + ")" + (regressed ? ": slower than before" : ": within limit"));
    }

    private static Result decide(List<Rule> rules, double eventsB, double eventsV, Window b, Window v,
            String throughputLabel, Map<String, String> evidence) {
        List<String> reasons = new ArrayList<>();
        boolean comparable = comparable(eventsB, eventsV, b, v);
        List<Rule> regressed = rules.stream().filter(Rule::regressed).toList();
        if (!comparable) {
            reasons.add("Throughput changed from " + rate(b.rate(eventsB)) + " to " + rate(v.rate(eventsV)) + " "
                    + throughputLabel + " per second (more than " + (int) THROUGHPUT_FACTOR + "x or no traffic): the"
                    + " windows are not comparable.");
            rules.forEach(r -> reasons.add(r.message()));
            return new Result(Verdict.INCONCLUSIVE, reasons, evidence);
        }
        if (!regressed.isEmpty()) {
            regressed.forEach(r -> reasons.add(r.message()));
            return new Result(Verdict.REGRESSED, reasons, evidence);
        }
        if (rules.stream().noneMatch(Rule::evaluated)) {
            reasons.add("Not enough traffic to verify the change: no rule reached its minimum sample count.");
            rules.forEach(r -> reasons.add(r.message()));
            return new Result(Verdict.INCONCLUSIVE, reasons, evidence);
        }
        rules.stream().filter(Rule::evaluated).forEach(r -> reasons.add(r.message()));
        return new Result(Verdict.OK, reasons, evidence);
    }

    private static boolean comparable(double eventsB, double eventsV, Window b, Window v) {
        double rateB = b.rate(eventsB);
        double rateV = v.rate(eventsV);
        if (rateB <= 0 || rateV <= 0) {
            return false;
        }
        double ratio = rateB > rateV ? rateB / rateV : rateV / rateB;
        return ratio <= THROUGHPUT_FACTOR;
    }

    private static void http(Window b, Window v, Map<String, String> evidence) {
        if (b.has(HTTP_REQUESTS) && v.has(HTTP_REQUESTS)) {
            evidence.put("httpMeanLatency (evidence only)", ms(ratio(b.get(HTTP_MS), b.get(HTTP_REQUESTS))) + " -> "
                    + ms(ratio(v.get(HTTP_MS), v.get(HTTP_REQUESTS))));
            evidence.put("http5xx (evidence only)", count(b.get(HTTP_5XX)) + " -> " + count(v.get(HTTP_5XX)));
        }
    }

    private static double ratio(double numerator, double denominator) {
        return denominator > 0 ? numerator / denominator : 0;
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, "%.1f ms", value);
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100);
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.1f/s", value);
    }

    private static String count(double value) {
        return String.valueOf(Math.round(value));
    }

    private static String seconds(Window w) {
        return Math.round(w.seconds()) + " s";
    }
}
