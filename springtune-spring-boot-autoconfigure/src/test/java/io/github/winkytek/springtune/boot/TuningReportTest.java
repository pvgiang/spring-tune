package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.model.Knob;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Report construction details that the web tests do not pin down. */
class TuningReportTest {

    private static TuningSnapshot.Component component(String id) {
        return new TuningSnapshot.Component(id, 1,
                List.of(new Knob(id + ".maximumPoolSize", id, "maximumPoolSize", 5, null, null, null, "count", "s")),
                Map.of());
    }

    @Test
    void pseudonymsAreOrdinalsPerKindInComponentIdOrder() {
        TuningSnapshot snapshot = new TuningSnapshot("ADVISE", false, Instant.EPOCH,
                new TuningSnapshot.Budget(null, null, 0.8, null),
                List.of(component("hikari:zeta"), component("executor:b"), component("hikari:alpha"),
                        component("executor:a")),
                List.of(), List.of(), null);
        TuningReport report = TuningReport.from(snapshot, "0.4.2", "3.5.16", 17);
        assertThat(report.components()).extracting(TuningReport.Component::ref)
                .containsExactly("executor#1", "executor#2", "hikari#1", "hikari#2");
        assertThat(report.components()).extracting(TuningReport.Component::kind)
                .containsExactly("executor", "executor", "hikari", "hikari");
        assertThat(report.springtuneVersion()).isEqualTo("0.4.2");
        assertThat(report.springBootVersion()).isEqualTo("3.5.16");
        assertThat(report.javaVersion()).isEqualTo(17);
        assertThat(report.verdicts()).isEmpty();
    }

    @Test
    void versionsOutsideTheGrammarBecomeUnknown() {
        TuningSnapshot snapshot = new TuningSnapshot("ADVISE", false, Instant.EPOCH,
                new TuningSnapshot.Budget(null, null, 0.8, null), List.of(), List.of(), List.of(), null);
        TuningReport report = TuningReport.from(snapshot, "1.0.0 orders-db.prod.internal", null, 21);
        assertThat(report.springtuneVersion()).isEqualTo("unknown");
        assertThat(report.springBootVersion()).isEqualTo("unknown");
    }

    @Test
    void starterAndBootVersionsAreRead() {
        assertThat(TuningReport.starterVersion()).matches("^\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?$");
        assertThat(TuningReport.bootVersion()).matches("^[34]\\.\\d+\\.\\d+$");
    }

    @Test
    void evidenceGrammar() {
        assertThat(List.of("12", "80%", "15 ms", "0.5s", "5m", "24h", "1h30m", "3 of 10", "-1"))
                .allMatch(TuningReport::numeric);
        assertThat(List.of("10.1.2.3", "unbounded", "no", "", "5432/orders", "2 (core 2, max 4)", "1e9", "h"))
                .noneMatch(TuningReport::numeric);
    }
}
