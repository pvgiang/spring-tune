package io.github.pvgiang.springtune.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pvgiang.springtune.model.Recommendation.Action;
import io.github.pvgiang.springtune.model.Recommendation.Confidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelInvariantsTest {

    @Test
    void severityOrdering() {
        assertThat(Severity.ERROR.isAtLeast(Severity.WARNING)).isTrue();
        assertThat(Severity.INFO.isAtLeast(Severity.WARNING)).isFalse();
        assertThat(Severity.WARNING.isAtLeast(Severity.WARNING)).isTrue();
        assertThat(Severity.parse(" warning ")).isEqualTo(Severity.WARNING);
        assertThatThrownBy(() -> Severity.parse("fatal")).hasMessageContaining("ERROR, WARNING, INFO");
    }

    @Test
    void ruleIdsMustFollowConvention() {
        new RuleDescriptor("ST-HIKARI-002", "t", Severity.INFO, "hikari", "s");
        new RuleDescriptor("ST-REC-HIKARI-001", "t", Severity.INFO, "hikari", "s");
        assertThatThrownBy(() -> new RuleDescriptor("HIKARI-2", "t", Severity.INFO, "hikari", "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locationRequiresPositiveLine() {
        assertThat(Location.fileLevel("a.yml").toString()).isEqualTo("a.yml:1");
        assertThat(new Location("a.yml", 3, 5).toString()).isEqualTo("a.yml:3:5");
        assertThatThrownBy(() -> new Location("a.yml", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suppressionNeedsReason() {
        assertThatThrownBy(() -> new Suppression("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintIgnoresLineNumbers() {
        Finding a = finding(new Location("a.yml", 3, 1));
        Finding b = finding(new Location("a.yml", 9, 4));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void recommendationActionMustMatchValues() {
        assertThatThrownBy(() -> rec(Action.INCREASE, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rec(Action.DECREASE, 10, 12)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rec(Action.HOLD, 10, 12)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rec(Action.INCREASE, 10, 13).recommendedValue()).isEqualTo(13);
    }

    private static Finding finding(Location location) {
        return new Finding("ST-HIKARI-002", Severity.WARNING, ".", "spring.datasource.hikari.leak-detection-threshold",
                "m", "w", "f", location, null);
    }

    private static Recommendation rec(Action action, long from, long to) {
        return new Recommendation("ST-REC-HIKARI-001", "k", action, from, to, "r", Map.of(), Confidence.LOW,
                List.of(), Instant.EPOCH);
    }
}
