package io.github.winky.springtune.check.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.testing.Checks;
import io.github.winky.springtune.check.testing.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SarifReporterTest {

    @TempDir
    Path root;

    @Test
    void producesSarif210WithRulesLevelsRegionsAndSuppressions() throws Exception {
        TestProject.in(root).pom(".", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "spring.datasource.hikari.connection-timeout: 0\n")
                .file("springtune.yml", "suppress:\n  - rule: ST-HIKARI-002\n    reason: tracked in JIRA-1\n");
        CheckReport report = Checks.run(root);
        JsonNode sarif = new ObjectMapper().readTree(Reporters.render(report, ReportFormat.SARIF));

        assertThat(sarif.get("version").asText()).isEqualTo("2.1.0");
        assertThat(sarif.get("$schema").asText()).contains("sarif-2.1.0");
        JsonNode run = sarif.get("runs").get(0);
        JsonNode rules = run.at("/tool/driver/rules");
        assertThat(rules.size()).isEqualTo(13);
        rules.forEach(rule -> {
            assertThat(rule.get("id").asText()).startsWith("ST-");
            assertThat(rule.get("helpUri").asText()).endsWith(rule.get("id").asText() + ".md");
            assertThat(rule.at("/defaultConfiguration/level").asText()).isIn("error", "warning", "note");
        });

        JsonNode timeout = find(run.get("results"), "ST-HIKARI-001");
        assertThat(timeout.get("level").asText()).isEqualTo("error");
        assertThat(rules.get(timeout.get("ruleIndex").asInt()).get("id").asText()).isEqualTo("ST-HIKARI-001");
        JsonNode location = timeout.at("/locations/0/physicalLocation");
        assertThat(location.at("/artifactLocation/uri").asText()).isEqualTo("src/main/resources/application.yml");
        assertThat(location.at("/artifactLocation/uriBaseId").asText()).isEqualTo("%SRCROOT%");
        assertThat(location.at("/region/startLine").asInt()).isEqualTo(1);
        assertThat(timeout.at("/message/text").asText()).contains("Why:", "Fix:");
        assertThat(timeout.at("/partialFingerprints/springtune~1v1").asText()).isNotBlank();

        JsonNode leak = find(run.get("results"), "ST-HIKARI-002");
        assertThat(leak.at("/suppressions/0/kind").asText()).isEqualTo("external");
        assertThat(leak.at("/suppressions/0/justification").asText()).isEqualTo("tracked in JIRA-1");
    }

    @Test
    void allFormatsRenderAndAreStable() {
        TestProject.in(root).pom(".", "spring-boot-starter-jdbc").resource(".", "application.yml", "a: 1\n");
        CheckReport report = Checks.run(root);
        for (ReportFormat format : ReportFormat.values()) {
            assertThat(Reporters.render(report, format)).isEqualTo(Reporters.render(Checks.run(root), format));
        }
        assertThat(Reporters.render(report, ReportFormat.MARKDOWN)).contains("# SpringTune report", "## Not evaluated");
        assertThat(Reporters.render(report, ReportFormat.TEXT)).contains("WARNING ST-HIKARI-001");
    }

    private static JsonNode find(JsonNode results, String ruleId) {
        for (JsonNode result : results) {
            if (result.get("ruleId").asText().equals(ruleId)) {
                return result;
            }
        }
        throw new AssertionError("No result for " + ruleId);
    }
}
