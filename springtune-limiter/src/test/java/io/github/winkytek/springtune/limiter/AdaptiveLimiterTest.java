package io.github.winkytek.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Decision;
import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Outcome;
import io.github.winkytek.springtune.limiter.AdaptiveLimiter.Permit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The facade over a real Netflix SimpleLimiter (no mocks); min = max pins the limit so decisions are exact. */
class AdaptiveLimiterTest {

    private static LimiterProperties fixed(int limit, boolean enforce, long warmup) {
        LimiterProperties properties = new LimiterProperties();
        properties.setInitialLimit(limit);
        properties.setMinLimit(limit);
        properties.setMaxLimit(limit);
        properties.setEnforce(enforce);
        properties.setWarmupSamples(warmup);
        return properties;
    }

    @Test
    void statusMapping() {
        assertThat(Outcome.forStatus(200)).isEqualTo(Outcome.SUCCESS);
        assertThat(Outcome.forStatus(404)).isEqualTo(Outcome.SUCCESS);
        assertThat(Outcome.forStatus(499)).isEqualTo(Outcome.SUCCESS);
        assertThat(Outcome.forStatus(500)).isEqualTo(Outcome.IGNORE);
        assertThat(Outcome.forStatus(503)).isEqualTo(Outcome.IGNORE);
    }

    @Test
    void successAndDroppedAreSamplesIgnoreIsNot() {
        AdaptiveLimiter limiter = AdaptiveLimiter.create(fixed(5, true, 0));
        Permit ok = limiter.acquire();
        Permit failed = limiter.acquire();
        Permit timedOut = limiter.acquire();
        assertThat(limiter.inflight()).isEqualTo(3);

        ok.release(Outcome.forStatus(200));
        failed.release(Outcome.forStatus(502));
        timedOut.release(Outcome.DROPPED);

        assertThat(limiter.inflight()).isZero();
        assertThat(limiter.completedSamples()).isEqualTo(2);
        assertThat(limiter.count(Decision.ACCEPTED)).isEqualTo(3);
    }

    @Test
    void releaseIsIdempotent() {
        AdaptiveLimiter limiter = AdaptiveLimiter.create(fixed(1, true, 0));
        Permit permit = limiter.acquire();
        permit.release(Outcome.SUCCESS);
        permit.release(Outcome.SUCCESS);
        permit.release(Outcome.DROPPED);
        assertThat(limiter.inflight()).isZero();
        assertThat(limiter.completedSamples()).isEqualTo(1);
        // A double release must not have handed out an extra permit.
        Permit first = limiter.acquire();
        assertThat(first.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(limiter.acquire().decision()).isEqualTo(Decision.REJECTED);
        first.release(Outcome.SUCCESS);
    }

    @Test
    void enforceRejectsOverTheLimitAfterWarmup() {
        AdaptiveLimiter limiter = AdaptiveLimiter.create(fixed(2, true, 3));
        List<Permit> held = new ArrayList<>(List.of(limiter.acquire(), limiter.acquire()));

        // Warmup: over the limit, but let through without a permit.
        Permit warm = limiter.acquire();
        assertThat(warm.decision()).isEqualTo(Decision.SHADOW_REJECTED);
        assertThat(warm.isRejected()).isFalse();
        warm.release(Outcome.SUCCESS); // no permit: a no-op
        assertThat(limiter.completedSamples()).isZero();
        assertThat(limiter.inflight()).isEqualTo(2);

        // Two samples are not enough ...
        held.forEach(p -> p.release(Outcome.SUCCESS));
        held = new ArrayList<>(List.of(limiter.acquire(), limiter.acquire()));
        assertThat(limiter.isWarmedUp()).isFalse();
        assertThat(limiter.acquire().decision()).isEqualTo(Decision.SHADOW_REJECTED);

        // ... the third completes warmup; 5xx (IGNORE) would not have counted.
        held.get(0).release(Outcome.forStatus(500));
        assertThat(limiter.isWarmedUp()).isFalse();
        held.get(1).release(Outcome.SUCCESS);
        assertThat(limiter.isWarmedUp()).isTrue();

        Permit a = limiter.acquire();
        Permit b = limiter.acquire();
        Permit c = limiter.acquire();
        assertThat(c.decision()).isEqualTo(Decision.REJECTED);
        assertThat(c.isRejected()).isTrue();
        assertThat(limiter.count(Decision.REJECTED)).isEqualTo(1);
        assertThat(limiter.count(Decision.SHADOW_REJECTED)).isEqualTo(2);
        a.release(Outcome.SUCCESS);
        b.release(Outcome.SUCCESS);
        assertThat(limiter.inflight()).isZero();
    }

    @Test
    void shadowNeverRejects() {
        AdaptiveLimiter limiter = AdaptiveLimiter.create(fixed(1, false, 0));
        assertThat(limiter.isWarmedUp()).isTrue();
        Permit held = limiter.acquire();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.acquire().isRejected()).isFalse();
        }
        assertThat(limiter.count(Decision.SHADOW_REJECTED)).isEqualTo(10);
        assertThat(limiter.count(Decision.REJECTED)).isZero();
        held.release(Outcome.SUCCESS);
    }

    @Test
    void limitStaysWithinMinAndMaxForEveryAlgorithm() throws Exception {
        for (LimiterProperties.Algorithm algorithm : LimiterProperties.Algorithm.values()) {
            LimiterProperties properties = new LimiterProperties();
            properties.setAlgorithm(algorithm);
            properties.setInitialLimit(20);
            properties.setMinLimit(10);
            properties.setMaxLimit(30);
            properties.setWarmupSamples(0);
            properties.setEnforce(true);
            AdaptiveLimiter limiter = AdaptiveLimiter.create(properties);
            assertThat(limiter.limit()).isEqualTo(20);
            // Every request dropped: each algorithm backs off, but never below min-limit.
            for (int i = 0; i < 2000; i++) {
                limiter.acquire().release(Outcome.DROPPED);
            }
            assertThat(limiter.limit()).as(algorithm.name()).isBetween(10, 30);
            // Fast successes at high concurrency: grows, but never above max-limit.
            for (int i = 0; i < 2000; i++) {
                List<Permit> permits = new ArrayList<>();
                for (int j = 0; j < limiter.limit(); j++) {
                    permits.add(limiter.acquire());
                }
                permits.forEach(p -> p.release(Outcome.SUCCESS));
            }
            assertThat(limiter.limit()).as(algorithm.name()).isBetween(10, 30);
            assertThat(limiter.inflight()).isZero();
        }
    }

    @Test
    void invalidSettingsFailFast() {
        LimiterProperties minAboveMax = new LimiterProperties();
        minAboveMax.setMinLimit(50);
        minAboveMax.setMaxLimit(40);
        assertThatIllegalStateException().isThrownBy(() -> AdaptiveLimiter.create(minAboveMax))
                .withMessageContaining("min-limit (50)").withMessageContaining("max-limit (40)");

        LimiterProperties initialOutside = new LimiterProperties();
        initialOutside.setInitialLimit(5);
        assertThatIllegalStateException().isThrownBy(() -> AdaptiveLimiter.create(initialOutside))
                .withMessageContaining("initial-limit");

        LimiterProperties badStatus = new LimiterProperties();
        badStatus.setRejectStatus(200);
        assertThatIllegalStateException().isThrownBy(() -> AdaptiveLimiter.create(badStatus))
                .withMessageContaining("reject-status");
    }

    @Test
    void retryAfterRoundsUp() {
        LimiterProperties properties = new LimiterProperties();
        assertThat(properties.retryAfterSeconds()).isEqualTo(1);
        properties.setRetryAfter(java.time.Duration.ofMillis(1500));
        assertThat(properties.retryAfterSeconds()).isEqualTo(2);
        properties.setRetryAfter(java.time.Duration.ZERO);
        assertThat(properties.retryAfterSeconds()).isZero();
    }
}
