package io.github.pvgiang.springtune.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pvgiang.springtune.check.testing.Checks;
import io.github.pvgiang.springtune.check.testing.TestProject;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckEngineTest {

    @TempDir
    Path root;

    private TestProject jdbcApp() {
        return TestProject.in(root).pom(".", "spring-boot-starter-jdbc").resource(".", "application.yml", "a: 1\n");
    }

    @Test
    void allThirteenBuiltInRulesAreRegistered() {
        assertThat(new CheckEngine().descriptors()).extracting(d -> d.id()).containsExactly(
                "ST-ASYNC-001", "ST-ASYNC-002", "ST-HIKARI-001", "ST-HIKARI-002", "ST-HIKARI-003", "ST-HIKARI-004",
                "ST-HIKARI-005", "ST-HTTP-001", "ST-HTTP-002", "ST-R4J-001", "ST-R4J-002", "ST-TOMCAT-001",
                "ST-TX-001");
    }

    @Test
    void severityOverrideAndDisableApply() {
        jdbcApp().file("springtune.yml", """
                rules:
                  ST-HIKARI-001:
                    severity: ERROR
                  st-hikari-002:
                    enabled: false
                """);
        CheckReport report = Checks.run(root);
        assertThat(Checks.findings(report, "ST-HIKARI-001")).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        assertThat(Checks.findings(report, "ST-HIKARI-002")).isEmpty();
    }

    @Test
    void suppressedFindingsAreReportedButDoNotBlock() {
        jdbcApp().file("springtune.yml", """
                suppress:
                  - rule: ST-HIKARI-001
                    reason: "Batch service; callers tolerate 30 s"
                """);
        CheckReport report = Checks.run(root);
        Finding finding = Checks.findings(report, "ST-HIKARI-001").get(0);
        assertThat(finding.isSuppressed()).isTrue();
        assertThat(finding.suppression().reason()).contains("Batch service");
        assertThat(report.blocking(Severity.INFO)).noneMatch(f -> f.ruleId().equals("ST-HIKARI-001"));
        assertThat(report.suppressedCount()).isEqualTo(1);
    }

    @Test
    void suppressionCanBeNarrowedToModuleAndSubject() {
        jdbcApp().file("springtune.yml", """
                suppress:
                  - rule: ST-HIKARI-001
                    module: other-module
                    reason: "not this one"
                """);
        assertThat(Checks.findings(Checks.run(root), "ST-HIKARI-001").get(0).isSuppressed()).isFalse();
    }

    @Test
    void suppressionWithoutReasonIsRejected() {
        jdbcApp().file("springtune.yml", "suppress:\n  - rule: ST-HIKARI-001\n");
        assertThatThrownBy(() -> Checks.run(root)).isInstanceOf(CheckException.class)
                .hasMessageContaining("suppress[0].reason is required");
    }

    @Test
    void invalidSettingsListEveryProblem() {
        jdbcApp().file("springtune.yml", """
                failOn: SOMETIMES
                databases:
                  - name: main
                services:
                  - path: orders
                    database: missing
                rules:
                  ST-NOPE-001:
                    enabled: false
                """);
        assertThatThrownBy(() -> Checks.run(root)).isInstanceOf(CheckException.class)
                .hasMessageContaining("failOn must be one of")
                .hasMessageContaining("databases[0].maxConnections is required")
                .hasMessageContaining("services[0].database 'missing' is not declared")
                .hasMessageContaining("rules.ST-NOPE-001 refers to an unknown rule id");
    }

    @Test
    void blockingRespectsThreshold() {
        jdbcApp();
        CheckReport report = Checks.run(root);
        assertThat(report.blocking(Severity.ERROR)).isEmpty();
        assertThat(report.blocking(Severity.WARNING)).isNotEmpty();
        assertThat(report.blocking(null)).isEmpty();
        assertThat(FailThreshold.parse("none")).isNull();
        assertThat(FailThreshold.parse(null)).isEqualTo(Severity.ERROR);
        assertThatThrownBy(() -> FailThreshold.parse("x")).isInstanceOf(CheckException.class);
    }

    @Test
    void discoveryFindsModulesAndSkipsBuildOutputAndTests() {
        TestProject.in(root)
                .resource("svc-a", "application.yml", "a: 1\n")
                .java("svc-b", "App", "package demo;\n@SpringBootApplication class App {}\n")
                .file("lib/src/main/java/demo/Util.java", "package demo; class Util {}")
                .file("svc-a/target/classes/application.yml", "a: 1\n")
                .file("svc-c/src/test/resources/application.yml", "a: 1\n")
                .file(".hidden/src/main/resources/application.yml", "a: 1\n");
        CheckReport report = Checks.run(root);
        assertThat(report.modules()).extracting(CheckReport.ModuleSummary::path).containsExactly("svc-a", "svc-b");
    }

    @Test
    void explicitProfilesOverrideConfiguredOnes() {
        jdbcApp().resource(".", "application-prod.yml", "spring.datasource.hikari.connection-timeout: 2000\n")
                .file("springtune.yml", "profiles: [dev]\n");
        assertThat(Checks.findings(Checks.run(root), "ST-HIKARI-001")).hasSize(1);
        assertThat(Checks.findings(Checks.run(root, "prod"), "ST-HIKARI-001")).isEmpty();
    }

    @Test
    void reportIsDeterministic() {
        jdbcApp().java(".", "C", "package demo;\nclass C { Object a = new RestTemplate(); Object b = WebClient.create(); }\n");
        List<Finding> first = Checks.run(root).findings();
        List<Finding> second = Checks.run(root).findings();
        assertThat(first).isEqualTo(second);
    }
}
