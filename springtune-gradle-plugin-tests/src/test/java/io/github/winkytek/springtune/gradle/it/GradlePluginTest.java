package io.github.winkytek.springtune.gradle.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradlePluginTest {

    @TempDir
    Path dir;

    /** The Spring Boot 3.5 Gradle plugin itself uses APIs deprecated in Gradle 9 (Configuration.setVisible). */
    private GradleBuild catalogApp() {
        return GradleBuild.of("catalog-app", dir).warningMode("all");
    }

    @Test
    void versionCatalogBuildFailsOnRiskyConfigNextToTheSpringBootPlugin() throws IOException {
        GradleBuild build = catalogApp();

        GradleBuild.Result result = build.run("springtuneCheck", "--configuration-cache");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.output())
                .contains("ERROR ST-HIKARI-001 src/main/resources/application.yml:4")
                .contains("connectionTimeout is 0")
                .contains("SpringTune: 1 finding(s) at or above ERROR in :springtuneCheck");
        Path reports = build.dir().resolve("build/springtune");
        assertThat(Files.readString(reports.resolve("springtune-report.sarif")))
                .contains("\"ruleId\" : \"ST-HIKARI-001\"")
                .contains("\"uri\" : \"src/main/resources/application.yml\"");
        assertThat(reports.resolve("springtune-report.md")).isRegularFile();
        // Only the resolved classpath reveals JPA/HikariCP and the Boot version: the build script names neither.
        assertThat(Files.readString(reports.resolve("springtune-report.json")))
                .contains("\"springBootVersion\" : \"3.5.16\"")
                .contains("\"hikari\"");

        GradleBuild.Result again = build.run("springtuneCheck", "--configuration-cache");
        assertThat(again.output()).contains("Reusing configuration cache.").contains("ST-HIKARI-001");
        assertThat(again.succeeded()).isFalse();
    }

    @Test
    void checkLifecycleRunsSpringtuneCheck() {
        GradleBuild build = catalogApp();

        GradleBuild.Result result = build.run("check", "--dry-run");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains(":springtuneCheck SKIPPED");
    }

    @Test
    void multiProjectBuildUsesRootSettingsProfilesAndCommandLineOverrides() throws IOException {
        GradleBuild build = GradleBuild.of("multi-project", dir);

        // app/build.gradle sets failOn = 'INFO', so the INFO finding blocks.
        GradleBuild.Result strict = build.run("springtuneCheck");
        assertThat(strict.succeeded()).isFalse();
        assertThat(strict.output()).contains("SpringTune: 1 finding(s) at or above INFO in :app:springtuneCheck");

        // -Pspringtune.failOn wins over the build script.
        GradleBuild.Result result = build.run("springtuneCheck", "-Pspringtune.failOn=NONE");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains("> Task :lib:springtuneCheck").contains("> Task :app:springtuneCheck");
        // The library has no Spring Boot config: skipped, no reports.
        try (var reports = Files.list(build.dir().resolve("lib/build/springtune"))) {
            assertThat(reports).isEmpty();
        }
        String sarif = Files.readString(build.dir().resolve("app/build/springtune/springtune-report.sarif"));
        // Paths are relative to the root project, so code-scanning alerts land on the right file.
        assertThat(sarif).contains("\"uri\" : \"app/src/main/resources/application-prod.properties\"");
        // springtune.yml at the root project is picked up: the suppression carries its reason.
        assertThat(sarif).contains("Leak detection is enabled per environment by the platform team");
        // The prod profile (2000 ms) wins over the base file (30000 ms), so ST-HIKARI-001 does not fire.
        assertThat(sarif).doesNotContain("\"ruleId\" : \"ST-HIKARI-001\"");
    }

    @Test
    void unresolvedDependenciesAreReportedAndTheirRequestedNamesStillCount() {
        GradleBuild build = GradleBuild.of("unresolved-dependency", dir);

        GradleBuild.Result result = build.run("springtuneCheck", "-Pspringtune.failOn=NONE");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output())
                .contains("SpringTune: 1 runtime dependencies could not be resolved"
                        + " (com.zaxxer:HikariCP:0.0.0-does-not-exist)")
                // Detected from the requested module name alone: the config never mentions a datasource.
                .contains("WARNING ST-HIKARI-001");
    }
}
