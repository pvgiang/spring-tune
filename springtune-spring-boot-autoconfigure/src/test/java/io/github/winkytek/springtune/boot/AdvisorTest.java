package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Recommendation rules over synthetic signals (one sample every 10 s). */
class AdvisorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private final SpringTuneProperties.Hikari hikari = new SpringTuneProperties().getHikari();
    private final SpringTuneProperties.Executor executor = new SpringTuneProperties().getExecutor();

    /** {@code count} samples; pending for the first {@code pendingSamples}; each sample adds 10 borrows of {@code holdMs}. */
    private static List<HikariSample> hikari(int count, int pendingSamples, double holdMs, int active, boolean timers) {
        List<HikariSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new HikariSample(T0.plusSeconds(10L * i), active, 10 - active, i < pendingSamples ? 4 : 0, 10,
                    timers, 10L * i, 10L * i * 30.0, 10L * i, 10L * i * holdMs));
        }
        return samples;
    }

    private static Instant end(List<? extends SampleWindow.Timed> samples) {
        return samples.get(samples.size() - 1).at();
    }

    private Optional<Recommendation> advise(List<HikariSample> window, List<HikariSample> longWindow,
            Optional<Integer> cap) {
        Instant now = end(window.isEmpty() ? longWindow : window);
        return HikariAdvisor.advise("hikari.p.maximumPoolSize", 10, window, longWindow, cap, hikari, now);
    }

    @Test
    void saturatedPoolWithFastHoldsIsRaisedWithinBudget() {
        Recommendation rec = advise(hikari(31, 31, 40, 10, true), List.of(), Optional.of(13)).orElseThrow();
        assertThat(rec.ruleId()).isEqualTo(HikariAdvisor.RAISE);
        assertThat(rec.action()).isEqualTo(Action.INCREASE);
        assertThat(rec.recommendedValue()).isEqualTo(13);
        assertThat(rec.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(rec.evidence()).containsEntry("meanConnectionHoldTime", "40 ms").containsEntry("budgetCap", "13");
    }

    @Test
    void stepIsCappedByBudgetAndHeldAtCap() {
        assertThat(advise(hikari(31, 31, 40, 10, true), List.of(), Optional.of(11)).orElseThrow().recommendedValue())
                .isEqualTo(11);
        Recommendation atCap = advise(hikari(31, 31, 40, 10, true), List.of(), Optional.of(10)).orElseThrow();
        assertThat(atCap.action()).isEqualTo(Action.HOLD);
        assertThat(atCap.reason()).contains("already at its connection budget cap of 10");
        Recommendation aboveCap = advise(hikari(31, 31, 40, 10, true), List.of(), Optional.of(6)).orElseThrow();
        assertThat(aboveCap.action()).isEqualTo(Action.HOLD);
        assertThat(aboveCap.reason()).contains("already above its connection budget cap of 6 (current 10)");
    }

    @Test
    void withoutBudgetConfidenceIsMediumAndReasonSaysSo() {
        Recommendation rec = advise(hikari(31, 31, 40, 10, true), List.of(), Optional.empty()).orElseThrow();
        assertThat(rec.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(rec.reason()).contains("No connection budget is configured");
    }

    @Test
    void longHoldsMeanDoNotRaise() {
        Recommendation rec = advise(hikari(31, 31, 900, 10, true), List.of(), Optional.of(50)).orElseThrow();
        assertThat(rec.ruleId()).isEqualTo(HikariAdvisor.HOLD_SLOW);
        assertThat(rec.action()).isEqualTo(Action.HOLD);
        assertThat(rec.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(rec.reason()).startsWith("Do not raise the pool").contains("900 ms");
        assertThat(rec.relatedRuleIds()).contains("ST-TX-001");
    }

    @Test
    void missingTimersYieldLowConfidenceHold() {
        Recommendation rec = advise(hikari(31, 31, 40, 10, false), List.of(), Optional.empty()).orElseThrow();
        assertThat(rec.action()).isEqualTo(Action.HOLD);
        assertThat(rec.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void occasionalWaitingIsNotSaturation() {
        assertThat(advise(hikari(31, 20, 40, 10, true), List.of(), Optional.empty())).isEmpty();
    }

    @Test
    void lowUtilizationOverLongWindowLowersPool() {
        Recommendation rec = advise(List.of(), hikari(31, 0, 5, 1, true), Optional.empty()).orElseThrow();
        assertThat(rec.ruleId()).isEqualTo(HikariAdvisor.LOWER);
        assertThat(rec.action()).isEqualTo(Action.DECREASE);
        assertThat(rec.recommendedValue()).isEqualTo(2);
        assertThat(rec.reason()).contains("frees 8 database connections");
    }

    @Test
    void anyWaitingBlocksLowering() {
        assertThat(advise(List.of(), hikari(31, 1, 5, 1, true), Optional.empty())).isEmpty();
    }

    @Test
    void windowRequiresCoverageBeforeAdvising() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofMinutes(10));
        List<HikariSample> samples = hikari(31, 31, 40, 10, true);
        samples.subList(0, 10).forEach(window::add);
        Instant now = samples.get(9).at();
        assertThat(window.covering(now, Duration.ofMinutes(5), Duration.ofSeconds(10))).isEmpty();
        samples.subList(10, 31).forEach(window::add);
        assertThat(window.covering(end(samples), Duration.ofMinutes(5), Duration.ofSeconds(10))).hasSize(31);
    }

    @Test
    void retentionDropsOldSamples() {
        SampleWindow<HikariSample> window = new SampleWindow<>(Duration.ofMinutes(1));
        hikari(31, 0, 5, 1, true).forEach(window::add);
        assertThat(window.size()).isEqualTo(7);
    }

    private static List<ExecutorSample> executor(int count, int queued, int capacity, int poolSize, int core, int max) {
        List<ExecutorSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new ExecutorSample(T0.plusSeconds(10L * i), queued, capacity, poolSize, poolSize, core, max));
        }
        return samples;
    }

    @Test
    void unboundedQueueBacklogRaisesCoreSize() {
        List<ExecutorSample> samples = executor(31, 50, Integer.MAX_VALUE, 8, 8, Integer.MAX_VALUE);
        Recommendation rec = ExecutorAdvisor.advise("executor.app", samples, executor, end(samples)).orElseThrow();
        assertThat(rec.knobId()).isEqualTo("executor.app.corePoolSize");
        assertThat(rec.recommendedValue()).isEqualTo(10);
        assertThat(rec.reason()).contains("maxPoolSize has no effect");
    }

    @Test
    void boundedQueueAtMaxRaisesMaxSize() {
        List<ExecutorSample> samples = executor(31, 50, 100, 16, 4, 16);
        Recommendation rec = ExecutorAdvisor.advise("executor.app", samples, executor, end(samples)).orElseThrow();
        assertThat(rec.knobId()).isEqualTo("executor.app.maxPoolSize");
        assertThat(rec.recommendedValue()).isEqualTo(20);
    }

    @Test
    void noBacklogNoRecommendation() {
        List<ExecutorSample> samples = executor(31, 0, 100, 4, 4, 16);
        assertThat(ExecutorAdvisor.advise("executor.app", samples, executor, end(samples))).isEmpty();
    }
}
