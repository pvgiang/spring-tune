package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.winkytek.springtune.boot.RuleEvaluation.Status;
import io.github.winkytek.springtune.model.Recommendation;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Per-rule evaluation statuses ({@code evaluations[]}): computed in the same pass as the recommendation, so a status
 * is RECOMMENDING exactly when that rule produced the component's recommendation.
 */
class RuleEvaluationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TICK = Duration.ofSeconds(10);
    private final SpringTuneProperties properties = new SpringTuneProperties();
    private final SpringTuneProperties.Hikari hikari = properties.getHikari();

    private static Map<String, RuleEvaluation> byRule(TunedComponent.Evaluation evaluation) {
        return evaluation.rules().stream().collect(Collectors.toMap(RuleEvaluation::ruleId, Function.identity()));
    }

    private static HikariSample idle(Instant at, long i, boolean timers) {
        return new HikariSample(at, 1, 9, 0, 10, timers, 10 * i, 10 * i * 2.0, 10 * i, 10 * i * 5.0, 0, 10);
    }

    private TunedComponent.Evaluation evaluate(SampleWindow<HikariSample> window, Instant now, List<String> missing) {
        return HikariAdvisor.evaluate("hikari:p", "hikari.p.maximumPoolSize", 10,
                window.coverage(now, hikari.getWindow(), TICK), window.coverage(now, hikari.getLowUtilizationWindow(),
                        TICK), Optional.of(13), hikari, now, missing);
    }

    @Test
    void freshPoolIsCollectingWithSamplesAndSecondsSeenVersusNeeded() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofHours(25));
        Instant now = T0;
        for (int i = 0; i < 13; i++) { // two minutes: 13 samples at 0 s, 10 s, ..., 120 s
            now = T0.plus(TICK.multipliedBy(i));
            window.add(idle(now, i, true));
        }
        TunedComponent.Evaluation evaluation = evaluate(window, now, List.of());
        assertThat(evaluation.recommendations()).isEmpty();
        assertThat(evaluation.rules()).extracting(RuleEvaluation::ruleId).containsExactly(HikariAdvisor.RAISE,
                HikariAdvisor.HOLD_SLOW, HikariAdvisor.HOLD_STARVED, HikariAdvisor.LOWER);
        for (RuleEvaluation rule : evaluation.rules()) {
            assertThat(rule.status()).isEqualTo(Status.COLLECTING);
            assertThat(rule.component()).isEqualTo("hikari:p");
            assertThat(rule.seenSamples()).isEqualTo(13);
            assertThat(rule.minSamples()).isEqualTo(3);
            assertThat(rule.seenSeconds()).isEqualTo(120);
        }
        Map<String, RuleEvaluation> rules = byRule(evaluation);
        assertThat(rules.get(HikariAdvisor.RAISE).neededSeconds()).isEqualTo(290); // 5 m window minus one 10 s tick
        assertThat(rules.get(HikariAdvisor.LOWER).neededSeconds()).isEqualTo(24 * 3600 - 10);
    }

    @Test
    void noSampleYetIsCollectingFromZero() {
        TunedComponent.Evaluation evaluation = evaluate(new SampleWindow<>(Duration.ofHours(25)), T0, List.of());
        assertThat(evaluation.rules()).allSatisfy(rule -> {
            assertThat(rule.status()).isEqualTo(Status.COLLECTING);
            assertThat(rule.seenSamples()).isZero();
            assertThat(rule.seenSeconds()).isZero();
        });
    }

    @Test
    void healthyPoolAfterFiveMinutesIsQuietUntilTheLongWindowIsCovered() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofHours(25));
        Instant now = T0;
        for (int i = 0; i <= 31; i++) { // 310 s
            now = T0.plus(TICK.multipliedBy(i));
            window.add(new HikariSample(now, 5, 5, 0, 10, true, 10L * i, 20.0 * i, 10L * i, 50.0 * i, 0, 10));
        }
        TunedComponent.Evaluation evaluation = evaluate(window, now, List.of());
        assertThat(evaluation.recommendations()).isEmpty();
        Map<String, RuleEvaluation> rules = byRule(evaluation);
        for (String rule : List.of(HikariAdvisor.RAISE, HikariAdvisor.HOLD_SLOW, HikariAdvisor.HOLD_STARVED)) {
            assertThat(rules.get(rule).status()).as(rule).isEqualTo(Status.QUIET);
            assertThat(rules.get(rule).supersededBy()).isNull();
        }
        assertThat(rules.get(HikariAdvisor.LOWER).status()).isEqualTo(Status.COLLECTING);
        assertThat(rules.get(HikariAdvisor.LOWER).seenSeconds()).isEqualTo(310);
    }

    @Test
    void healthyPoolAfterTwentyFourHoursIsQuietOnEveryRule() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofHours(24).plus(TICK));
        Instant now = T0;
        int ticks = (int) (Duration.ofHours(24).toSeconds() / 10);
        for (int i = 0; i <= ticks; i++) { // half the pool in use, nobody waits: nothing to lower, nothing to raise
            now = T0.plus(TICK.multipliedBy(i));
            window.add(new HikariSample(now, 5, 5, 0, 10, true, 10L * i, 20.0 * i, 10L * i, 50.0 * i, 0, 10));
        }
        TunedComponent.Evaluation evaluation = evaluate(window, now, List.of());
        assertThat(evaluation.recommendations()).isEmpty();
        assertThat(evaluation.rules()).extracting(RuleEvaluation::status).containsOnly(Status.QUIET);
    }

    @Test
    void missingHoldTimeTimerIsNoSignalForRuleOneNamingTheMeter() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofHours(25));
        Instant now = T0;
        for (int i = 0; i <= 31; i++) {
            now = T0.plus(TICK.multipliedBy(i));
            window.add(idle(now, i, false));
        }
        Map<String, RuleEvaluation> rules = byRule(evaluate(window, now, List.of(HikariAdvisor.USAGE_TIMER)));
        RuleEvaluation raise = rules.get(HikariAdvisor.RAISE);
        assertThat(raise.status()).isEqualTo(Status.NO_SIGNAL);
        assertThat(raise.missingMeters()).containsExactly("hikaricp.connections.usage");
        assertThat(rules.get(HikariAdvisor.HOLD_SLOW).status()).isEqualTo(Status.QUIET);
        assertThat(rules.get(HikariAdvisor.HOLD_STARVED).status()).isEqualTo(Status.QUIET);
    }

    /** A real pool whose usage timer is missing: the component itself names the meter it cannot read. */
    @Test
    void realPoolWithoutUsageTimerReportsNoSignal() throws Exception {
        TimerReader acquireOnly = (name, pool) -> name.equals(HikariAdvisor.ACQUIRE_TIMER)
                ? Optional.of(new TimerReader.Totals(1, 1.0)) : Optional.empty();
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:evaluations;DB_CLOSE_DELAY=-1");
            dataSource.setPoolName("orders");
            dataSource.setMaximumPoolSize(4);
            dataSource.getConnection().close();
            HikariComponent component = new HikariComponent("dataSource", dataSource, acquireOnly, properties,
                    Optional.empty());
            Instant now = T0;
            for (int i = 0; i <= 31; i++) {
                now = T0.plus(TICK.multipliedBy(i));
                component.sample(now);
            }
            TunedComponent.Evaluation evaluation = component.evaluate(now);
            Map<String, RuleEvaluation> rules = byRule(evaluation);
            assertThat(rules.get(HikariAdvisor.RAISE).status()).isEqualTo(Status.NO_SIGNAL);
            assertThat(rules.get(HikariAdvisor.RAISE).missingMeters()).containsExactly(HikariAdvisor.USAGE_TIMER);
            assertThat(rules.get(HikariAdvisor.RAISE).component()).isEqualTo("hikari:orders");
            assertThat(evaluation.recommendations()).isEmpty();
            assertThat(component.recommend(now)).isEqualTo(evaluation.recommendations());
        }
    }

    @Test
    void precedenceIsReportedAsQuietSupersededBy() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofHours(25));
        Instant now = T0;
        for (int i = 0; i <= 31; i++) { // saturated with 900 ms holds: 002 HOLD
            now = T0.plus(TICK.multipliedBy(i));
            window.add(new HikariSample(now, 10, 0, 4, 10, true, 10L * i, 30.0 * i, 10L * i, 9000.0 * i, 0, 10));
        }
        TunedComponent.Evaluation evaluation = evaluate(window, now, List.of());
        assertThat(evaluation.recommendations()).extracting(Recommendation::ruleId).containsExactly(HikariAdvisor.HOLD_SLOW);
        Map<String, RuleEvaluation> rules = byRule(evaluation);
        assertThat(rules.get(HikariAdvisor.HOLD_SLOW).status()).isEqualTo(Status.RECOMMENDING);
        assertThat(rules.get(HikariAdvisor.RAISE).status()).isEqualTo(Status.QUIET);
        assertThat(rules.get(HikariAdvisor.RAISE).supersededBy()).isEqualTo(HikariAdvisor.HOLD_SLOW);
        assertThat(rules.get(HikariAdvisor.HOLD_STARVED).supersededBy()).isEqualTo(HikariAdvisor.HOLD_SLOW);
    }

    /**
     * Property test over random windows: for every rule, RECOMMENDING exactly when that rule produced the
     * recommendation; at most one recommendation per pool; {@link HikariAdvisor#advise} (the decision every caller
     * used before statuses existed) returns the same recommendation; every rule is reached.
     */
    @Test
    void recommendingExactlyWhenARecommendationIsPresent() {
        Random random = new Random(42);
        Set<String> fired = new HashSet<>();
        Set<Status> seen = new HashSet<>();
        for (int run = 0; run < 3000; run++) {
            int count = random.nextInt(4) == 0 ? random.nextInt(4) : 3 + random.nextInt(40);
            double pendingShare = random.nextDouble();
            double starvedShare = random.nextInt(3) == 0 ? random.nextDouble() : 0;
            boolean timers = random.nextInt(5) != 0;
            double holdMs = random.nextBoolean() ? 20 : 900;
            int peak = random.nextInt(11);
            boolean longCovered = random.nextInt(3) == 0;
            Optional<Integer> cap = random.nextBoolean() ? Optional.empty() : Optional.of(5 + random.nextInt(15));
            List<HikariSample> samples = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                boolean pending = random.nextDouble() < pendingShare;
                boolean starved = pending && random.nextDouble() < starvedShare;
                samples.add(new HikariSample(T0.plus(TICK.multipliedBy(i)), pending ? 10 : peak, 0, pending ? 3 : 0, 10,
                        timers, 10L * i, 30.0 * i, 10L * i, holdMs * 10 * i, random.nextInt(3) == 0 ? i : 0,
                        starved ? 6 : 10));
            }
            Instant now = samples.isEmpty() ? T0 : samples.get(samples.size() - 1).at();
            List<HikariSample> window = count >= 3 ? samples : List.of();
            List<HikariSample> longWindow = longCovered ? window : List.of();
            TunedComponent.Evaluation evaluation = HikariAdvisor.evaluate("hikari:p", "k", 10,
                    SampleWindow.Coverage.of(window, hikari.getWindow()),
                    SampleWindow.Coverage.of(longWindow, hikari.getLowUtilizationWindow()), cap, hikari, now,
                    List.of());
            assertRecommendingMatches(evaluation, HikariAdvisor.RULES);
            assertThat(evaluation.recommendations()).hasSizeLessThanOrEqualTo(1);
            assertThat(evaluation.recommendations().stream().findFirst())
                    .isEqualTo(adviseBeforeStatuses("k", 10, window, longWindow, cap, hikari, now));
            evaluation.recommendations().forEach(rec -> fired.add(rec.ruleId()));
            evaluation.rules().forEach(rule -> seen.add(rule.status()));
        }
        for (int run = 0; run < 1000; run++) {
            int count = random.nextInt(4) == 0 ? random.nextInt(4) : 3 + random.nextInt(40);
            double backlog = random.nextDouble();
            boolean unbounded = random.nextBoolean();
            boolean atMax = random.nextBoolean();
            List<ExecutorSample> samples = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                boolean queued = random.nextDouble() < backlog;
                samples.add(new ExecutorSample(T0.plus(TICK.multipliedBy(i)), queued ? 5 : 0,
                        unbounded ? Integer.MAX_VALUE : 100, 4, atMax ? 4 : 3, 2, 4));
            }
            Instant now = samples.isEmpty() ? T0 : samples.get(samples.size() - 1).at();
            List<ExecutorSample> window = count >= 3 ? samples : List.of();
            TunedComponent.Evaluation evaluation = ExecutorAdvisor.evaluate("executor:e", "executor.e",
                    SampleWindow.Coverage.of(window, properties.getExecutor().getWindow()), properties.getExecutor(),
                    now);
            assertRecommendingMatches(evaluation, List.of(ExecutorAdvisor.RULE));
            assertThat(evaluation.recommendations().stream().findFirst())
                    .isEqualTo(ExecutorAdvisor.advise("executor.e", window, properties.getExecutor(), now));
            evaluation.recommendations().forEach(rec -> fired.add(rec.ruleId()));
            evaluation.rules().forEach(rule -> seen.add(rule.status()));
        }
        assertThat(fired).containsExactlyInAnyOrder(HikariAdvisor.RAISE, HikariAdvisor.HOLD_SLOW,
                HikariAdvisor.HOLD_STARVED, HikariAdvisor.LOWER, ExecutorAdvisor.RULE);
        assertThat(seen).containsExactlyInAnyOrder(Status.values());
    }

    /**
     * {@code HikariAdvisor.advise} as it was before statuses existed (0.4.1), kept here as the oracle:
     * {@code advise} now delegates to {@code evaluate}, so comparing the two would be a tautology.
     */
    private static Optional<Recommendation> adviseBeforeStatuses(String knobId, int current,
            List<HikariSample> window, List<HikariSample> longWindow, Optional<Integer> budgetCap,
            SpringTuneProperties.Hikari settings, Instant now) {
        if (!window.isEmpty()) {
            double waiting = SampleWindow.ratio(window, s -> s.pending() > 0);
            if (waiting >= settings.getSaturatedRatio()) {
                return Optional.of(HikariAdvisor.saturated(knobId, current, window, waiting, budgetCap, settings, now));
            }
        }
        if (!longWindow.isEmpty() && longWindow.stream().allMatch(s -> s.pending() == 0)) {
            int peak = longWindow.stream().mapToInt(HikariSample::active).max().orElse(0);
            if ((double) peak / current < settings.getLowUtilization()) {
                int proposed = Math.max(2, peak * 2);
                if (proposed < current) {
                    Map<String, String> evidence = new java.util.LinkedHashMap<>();
                    evidence.put("window", Format.duration(settings.getLowUtilizationWindow()));
                    evidence.put("samples", String.valueOf(longWindow.size()));
                    evidence.put("peakActiveConnections", peak + " of " + current);
                    evidence.put("threadsEverWaiting", "no");
                    return Optional.of(new Recommendation(HikariAdvisor.LOWER, knobId,
                            Recommendation.Action.DECREASE, current, proposed,
                            "Over the last " + Format.duration(settings.getLowUtilizationWindow()) + " at most " + peak
                                    + " of " + current + " connections were in use and no thread ever waited."
                                    + " Lowering the pool to " + proposed + " frees " + (current - proposed)
                                    + " database connections per instance for other services.",
                            evidence, Recommendation.Confidence.MEDIUM, List.of("ST-HIKARI-004"), now));
                }
            }
        }
        return Optional.empty();
    }

    private static void assertRecommendingMatches(TunedComponent.Evaluation evaluation, List<String> rules) {
        assertThat(evaluation.rules()).extracting(RuleEvaluation::ruleId).containsExactlyElementsOf(rules);
        Set<String> recommending = evaluation.rules().stream().filter(r -> r.status() == Status.RECOMMENDING)
                .map(RuleEvaluation::ruleId).collect(Collectors.toSet());
        Set<String> recommended = evaluation.recommendations().stream().map(Recommendation::ruleId)
                .collect(Collectors.toSet());
        assertThat(recommending).isEqualTo(recommended);
    }

    @Test
    void executorIsCollectingThenQuiet() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.initialize();
        try {
            ExecutorComponent component = new ExecutorComponent("worker", executor, properties);
            component.sample(T0);
            RuleEvaluation first = component.evaluate(T0).rules().get(0);
            assertThat(first.status()).isEqualTo(Status.COLLECTING);
            assertThat(first.ruleId()).isEqualTo(ExecutorAdvisor.RULE);
            assertThat(first.seenSamples()).isEqualTo(1);
            assertThat(first.neededSeconds()).isEqualTo(290);
            Instant now = T0;
            for (int i = 1; i <= 31; i++) {
                now = T0.plus(TICK.multipliedBy(i));
                component.sample(now);
            }
            assertThat(component.evaluate(now).rules()).extracting(RuleEvaluation::status)
                    .containsExactly(Status.QUIET);
            assertThat(component.recommend(now)).isEmpty();
        } finally {
            executor.shutdown();
        }
    }

    /** The snapshot carries the statuses of the latest tick next to its recommendations. */
    @Test
    void snapshotListsEvaluationsOfTheLatestTick() throws Exception {
        try (HikariDataSource dataSource = new HikariDataSource();
                GenericApplicationContext context = new GenericApplicationContext()) {
            dataSource.setJdbcUrl("jdbc:h2:mem:evaluations-snapshot;DB_CLOSE_DELAY=-1");
            dataSource.setPoolName("orders");
            context.registerBean("dataSource", HikariDataSource.class, () -> dataSource);
            context.refresh();
            dataSource.getConnection().close();
            MutableClock clock = new MutableClock(T0);
            TuningService service = new TuningService(properties, context.getBeanFactory(), clock);
            assertThat(service.snapshot().evaluations()).as("before the first tick").isEmpty();
            service.sample();
            TuningSnapshot snapshot = service.snapshot();
            assertThat(snapshot.evaluations()).hasSize(4).allSatisfy(rule -> {
                assertThat(rule.component()).isEqualTo("hikari:orders");
                assertThat(rule.status()).isEqualTo(Status.COLLECTING);
                assertThat(rule.seenSamples()).isEqualTo(1);
            });
            assertThat(snapshot.recommendations()).isEmpty();
        }
    }
}
