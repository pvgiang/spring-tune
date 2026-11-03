package io.github.winky.springtune.samples.brokenconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.check.CheckEngine;
import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.CheckRequest;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Severity;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The CI check flags every deliberate problem in this sample, and nothing else. */
class StaticCheckTest {

    @Test
    void checkFlagsTheDeliberateProblems() {
        CheckReport report = new CheckEngine().run(CheckRequest.of(Path.of(".")));
        assertThat(report.findings()).extracting(Finding::ruleId).containsExactlyInAnyOrder(
                "ST-ASYNC-001", "ST-HTTP-001", "ST-HIKARI-001", "ST-HIKARI-002", "ST-HIKARI-003", "ST-HIKARI-004",
                "ST-TOMCAT-001", "ST-TX-001");
        assertThat(report.blocking(Severity.ERROR)).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("ST-HIKARI-003", "ST-HIKARI-004");
    }
}
