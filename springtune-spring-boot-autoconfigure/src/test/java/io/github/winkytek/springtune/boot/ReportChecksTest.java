package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The trap check itself: it must catch the hostile port and must not trip over digits inside a timestamp. */
class ReportChecksTest {

    @Test
    void aTimestampWhoseNanosecondsContainTheTrapPortIsNotALeak() {
        // Seen in CI: "finishedAt":"2026-11-15T06:37:07.788825432Z" failed the trap on "5432".
        assertThatCode(() -> ReportChecks.assertNoHostileValue(
                "{\"finishedAt\":\"2026-11-15T06:37:07.788825432Z\",\"samples\":15432}"))
                .doesNotThrowAnyException();
    }

    @Test
    void theTrapPortIsCaughtWhereverItStandsAsANumberOfItsOwn() {
        assertThatThrownBy(() -> ReportChecks.assertNoHostileValue("{\"x\":\"db:5432\"}"))
                .hasMessageContaining("5432");
        assertThatThrownBy(() -> ReportChecks.assertNoHostileValue("{\"x\":\"port 5432 refused\"}"))
                .hasMessageContaining("5432");
        assertThatThrownBy(() -> ReportChecks.assertNoHostileValue("{\"x\":5432}"))
                .hasMessageContaining("5432");
    }
}
