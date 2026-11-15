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

    /** Iteration 6 R4: the schema check must judge a leaf by its position, not accept any grammar token anywhere. */
    @Test
    void anUpperCaseWordInAnEnumFieldFailsTheSchemaCheck() {
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"mode\":\"ORDERSDB\"}"))
                .hasMessageContaining("$.mode");
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"evaluations\":[{\"status\":\"ORDERSDB\"}]}"))
                .hasMessageContaining("$.evaluations[].status");
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"verdicts\":[{\"code\":\"ORDERSDB\"}]}"))
                .hasMessageContaining("$.verdicts[].code");
        // Valid tokens in the wrong place fail too.
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"mode\":\"2026-11-15T06:37:07Z\"}"))
                .hasMessageContaining("$.mode");
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"components\":[{\"kind\":\"hikari#1\"}]}"))
                .hasMessageContaining("$.components[].kind");
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"recommendations\":[{\"ruleId\":\"apply\"}]}"))
                .hasMessageContaining("$.recommendations[].ruleId");
    }

    @Test
    void anUnknownPathFailsTheSchemaCheck() {
        assertThatThrownBy(() -> ReportChecks.assertSchema(
                "{\"format\":\"springtune-report/1\",\"note\":\"OK\"}"))
                .hasMessageContaining("$.note");
    }

    @Test
    void wellFormedLeavesPassTheSchemaCheck() {
        assertThatCode(() -> ReportChecks.assertSchema("{\"format\":\"springtune-report/1\",\"mode\":\"APPLY\","
                + "\"generatedAt\":\"2026-11-15T06:37:07.788825432Z\",\"springtuneVersion\":\"0.4.3-SNAPSHOT\","
                + "\"components\":[{\"ref\":\"hikari#1\",\"kind\":\"hikari\",\"samples\":3,"
                + "\"knobs\":{\"maximumPoolSize\":10}}],"
                + "\"evaluations\":[{\"component\":\"hikari#1\",\"ruleId\":\"ST-REC-HIKARI-001\","
                + "\"status\":\"COLLECTING\",\"missingMeters\":[\"hikaricp.connections.usage\"]}],"
                + "\"verdicts\":[{\"ref\":\"change-1\",\"action\":\"apply\",\"knob\":\"hikari#1.maximumPoolSize\","
                + "\"outcome\":\"REFUSED\",\"code\":\"SERVER_LIMIT_UNKNOWN\",\"before\":{},\"after\":{}}],"
                + "\"omitted\":[\"signals\"]}"))
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
