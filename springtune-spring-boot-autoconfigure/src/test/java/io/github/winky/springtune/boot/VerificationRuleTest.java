package io.github.winky.springtune.boot;

import static io.github.winky.springtune.boot.Verification.ACQUIRES;
import static io.github.winky.springtune.boot.Verification.ACQUIRE_MS;
import static io.github.winky.springtune.boot.Verification.COMPLETED;
import static io.github.winky.springtune.boot.Verification.HTTP_5XX;
import static io.github.winky.springtune.boot.Verification.HTTP_MS;
import static io.github.winky.springtune.boot.Verification.HTTP_REQUESTS;
import static io.github.winky.springtune.boot.Verification.REJECTED;
import static io.github.winky.springtune.boot.Verification.TIMEOUTS;
import static io.github.winky.springtune.boot.Verification.USAGES;
import static io.github.winky.springtune.boot.Verification.USAGE_MS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.boot.Verification.Counters;
import io.github.winky.springtune.boot.Verification.Result;
import io.github.winky.springtune.boot.Verification.Verdict;
import io.github.winky.springtune.boot.Verification.Window;
import io.github.winky.springtune.boot.WritableKnob.Kind;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verification rules R1-R3 (Hikari) and R6 (executor) on synthetic counter deltas over 300 s windows. */
class VerificationRuleTest {

    /** Hikari window: acquires, mean acquire ms, usages, mean hold ms, timeouts. */
    private static Window hikari(double acquires, double acquireMean, double usages, double holdMean, double timeouts) {
        Map<String, Double> delta = new LinkedHashMap<>();
        delta.put(ACQUIRES, acquires);
        delta.put(ACQUIRE_MS, acquires * acquireMean);
        delta.put(USAGES, usages);
        delta.put(USAGE_MS, usages * holdMean);
        delta.put(TIMEOUTS, timeouts);
        return new Window(300, delta);
    }

    private static Window executor(double completed, double rejected) {
        return new Window(300, Map.of(COMPLETED, completed, REJECTED, rejected));
    }

    private static final Window BASE = hikari(3000, 40, 3000, 15, 0);

    @Test
    void unchangedSignalsAreOk() {
        Result result = Verification.verify(Kind.HIKARI, BASE, hikari(3200, 10, 3200, 16, 0));
        assertThat(result.verdict()).isEqualTo(Verdict.OK);
        assertThat(result.reasons()).anyMatch(r -> r.startsWith("R2")).anyMatch(r -> r.startsWith("R3"));
        assertThat(result.evidence()).containsKeys("meanConnectionHoldTime", "meanAcquireTime", "timeoutsPerAttempt");
    }

    @Test
    void r1TimeoutsPerAttemptUpByMoreThanATenthOfAPoint() {
        // 4 timeouts in 3000 attempts: +0.13 pp -> regression.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(2996, 40, 2996, 15, 4)).verdict())
                .isEqualTo(Verdict.REGRESSED);
        // 3 timeouts in 6000 attempts: +0.05 pp -> not a regression.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(5997, 40, 5997, 15, 3)).verdict())
                .isEqualTo(Verdict.OK);
        // Only 2 timeouts: below the minimum even though the rate is +1 pp.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(198, 40, 198, 15, 2)).verdict())
                .isNotEqualTo(Verdict.REGRESSED);
    }

    @Test
    void r1IsTheFastFailRule() {
        assertThat(Verification.fastFail(Kind.HIKARI, BASE, hikari(2996, 40, 2996, 15, 4))).isTrue();
        assertThat(Verification.fastFail(Kind.HIKARI, BASE, hikari(3000, 400, 3000, 150, 0))).isFalse();
        assertThat(Verification.fastFail(Kind.EXECUTOR, executor(1000, 0), executor(1000, 50))).isFalse();
    }

    @Test
    void r2HoldTimeAboveOneAndAHalfTimesBaselinePlusFiveMs() {
        // Limit 1.5 x 15 + 5 = 27.5 ms.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(3000, 40, 3000, 27, 0)).verdict())
                .isEqualTo(Verdict.OK);
        Result slow = Verification.verify(Kind.HIKARI, BASE, hikari(3000, 40, 3000, 28, 0));
        assertThat(slow.verdict()).isEqualTo(Verdict.REGRESSED);
        assertThat(slow.reasons()).anyMatch(r -> r.startsWith("R2") && r.contains("limit 27.5 ms"));
    }

    @Test
    void r2NeedsHundredUsagesInBothWindows() {
        Result result = Verification.verify(Kind.HIKARI, hikari(99, 40, 99, 15, 0), hikari(99, 40, 99, 100, 0));
        assertThat(result.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(result.reasons()).anyMatch(r -> r.contains("Not enough traffic"));
    }

    @Test
    void r3AcquireTimeAboveOneAndAHalfTimesBaselinePlusFiveMs() {
        // Limit 1.5 x 40 + 5 = 65 ms.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(3000, 66, 3000, 15, 0)).verdict())
                .isEqualTo(Verdict.REGRESSED);
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(3000, 64, 3000, 15, 0)).verdict())
                .isEqualTo(Verdict.OK);
    }

    @Test
    void throughputShiftOfMoreThanTwoTimesIsInconclusive() {
        Result busier = Verification.verify(Kind.HIKARI, BASE, hikari(6100, 40, 6100, 30, 0));
        assertThat(busier.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(busier.reasons().get(0)).contains("not comparable");
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(1400, 40, 1400, 15, 0)).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
        // Within 2x: judged normally.
        assertThat(Verification.verify(Kind.HIKARI, BASE, hikari(5900, 40, 5900, 15, 0)).verdict())
                .isEqualTo(Verdict.OK);
        // No traffic at all.
        assertThat(Verification.verify(Kind.HIKARI, hikari(0, 0, 0, 0, 0), hikari(0, 0, 0, 0, 0)).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void missingTimersAreInconclusive() {
        Window empty = new Window(300, Map.of());
        assertThat(Verification.verify(Kind.HIKARI, empty, empty).verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void r6MoreRejectionsPerSecondThanBaseline() {
        assertThat(Verification.verify(Kind.EXECUTOR, executor(1000, 0), executor(1100, 1)).verdict())
                .isEqualTo(Verdict.REGRESSED);
        assertThat(Verification.verify(Kind.EXECUTOR, executor(1000, 5), executor(1100, 5)).verdict())
                .isEqualTo(Verdict.OK);
        assertThat(Verification.verify(Kind.EXECUTOR, executor(1000, 0), executor(1100, 0)).verdict())
                .isEqualTo(Verdict.OK);
        assertThat(Verification.verify(Kind.EXECUTOR, executor(50, 0), executor(60, 0)).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(Verification.verify(Kind.EXECUTOR, executor(1000, 0), executor(3000, 0)).verdict())
                .isEqualTo(Verdict.INCONCLUSIVE);
    }

    @Test
    void httpSignalsAreEvidenceOnly() {
        Map<String, Double> b = new LinkedHashMap<>(BASE.delta());
        b.put(HTTP_REQUESTS, 1000.0);
        b.put(HTTP_MS, 50_000.0);
        b.put(HTTP_5XX, 0.0);
        Map<String, Double> v = new LinkedHashMap<>(hikari(3000, 40, 3000, 15, 0).delta());
        v.put(HTTP_REQUESTS, 1000.0);
        v.put(HTTP_MS, 900_000.0);
        v.put(HTTP_5XX, 400.0);
        Result result = Verification.verify(Kind.HIKARI, new Window(300, b), new Window(300, v));
        assertThat(result.verdict()).isEqualTo(Verdict.OK);
        assertThat(result.evidence()).containsEntry("httpMeanLatency (evidence only)", "50.0 ms -> 900.0 ms")
                .containsEntry("http5xx (evidence only)", "0 -> 400");
    }

    @Test
    void windowsAreDeltasOfCumulativeCounters() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Window w = Window.between(new Counters(t0, Map.of(ACQUIRES, 100.0, USAGES, 50.0)),
                new Counters(t0.plusSeconds(60), Map.of(ACQUIRES, 400.0, USAGES, 80.0, TIMEOUTS, 1.0)));
        assertThat(w.seconds()).isEqualTo(60);
        assertThat(w.get(ACQUIRES)).isEqualTo(300);
        assertThat(w.get(USAGES)).isEqualTo(30);
        assertThat(w.has(TIMEOUTS)).as("a counter missing at the start has no delta").isFalse();
    }
}
