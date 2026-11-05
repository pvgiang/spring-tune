package io.github.winkytek.springtune.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.check.testing.Checks;
import io.github.winkytek.springtune.check.testing.TestProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Build facts resolved by the build tool (Gradle plugin) fill the gaps of reading build files literally. */
class ResolvedBuildTest {

    @TempDir
    Path root;

    /** A Gradle module whose dependencies come from a version catalog: nothing is readable literally. */
    private void versionCatalogModule() {
        TestProject.in(root)
                .file("build.gradle.kts", """
                        plugins { alias(libs.plugins.spring.boot) }
                        dependencies { implementation(libs.spring.boot.starter.data.jpa) }
                        """)
                .resource(".", "application.yml", "spring.application.name: orders\n");
    }

    private CheckReport run(Map<Path, ResolvedBuild> resolved) {
        return new CheckEngine().run(new CheckRequest(root, List.of(root), List.of(), null, resolved));
    }

    @Test
    void versionCatalogDependenciesAreInvisibleWithoutResolvedFacts() {
        versionCatalogModule();
        CheckReport report = Checks.run(root);
        assertThat(Checks.findings(report, "ST-HIKARI-001")).isEmpty();
        assertThat(report.modules().get(0).springBootVersion()).isNull();
    }

    @Test
    void resolvedClasspathEnablesTheRulesAndReportsTheRealBootVersion() {
        versionCatalogModule();
        CheckReport report = run(Map.of(root, new ResolvedBuild(
                Set.of("spring-boot-starter-data-jpa", "HikariCP", "spring-boot"), "3.5.16")));
        assertThat(Checks.findings(report, "ST-HIKARI-001")).hasSize(1);
        assertThat(report.modules().get(0).springBootVersion()).isEqualTo("3.5.16");
        assertThat(report.modules().get(0).technologies()).contains("hikari");
    }

    @Test
    void resolvedFactsAreMatchedByNormalizedModuleDirectory() {
        versionCatalogModule();
        CheckReport report = run(Map.of(root.resolve("x/..").resolve("."), new ResolvedBuild(Set.of("HikariCP"), null)));
        assertThat(Checks.findings(report, "ST-HIKARI-001")).hasSize(1);
    }

    @Test
    void resolvedFactsAddToDeclaredOnesAndOtherPoolsStillWin() {
        TestProject.in(root).pom(".", "spring-boot-starter-jdbc", "druid-spring-boot-starter")
                .resource(".", "application.yml", "a: 1\n");
        CheckReport report = run(Map.of(root, new ResolvedBuild(Set.of("HikariCP"), null)));
        assertThat(Checks.findings(report, "ST-HIKARI-001")).isEmpty();
    }
}
