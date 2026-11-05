package io.github.winkytek.springtune.check.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.check.Diagnostic;
import io.github.winkytek.springtune.check.DiagnosticSink;
import io.github.winkytek.springtune.check.testing.TestProject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigLoaderTest {

    @TempDir
    Path root;

    private final DiagnosticSink diagnostics = new DiagnosticSink();

    private ResolvedConfig load(String... profiles) {
        return new ProjectConfigLoader().load(root, root, List.of(profiles), diagnostics);
    }

    @Test
    void relaxedBindingMatchesCamelCaseAndKebabCase() {
        TestProject.in(root).resource(".", "application.properties",
                "spring.datasource.hikari.maximumPoolSize=25\n");
        PropertyValue<Integer> value = load().getInt("spring.datasource.hikari.maximum-pool-size");
        assertThat(value.isSet()).isTrue();
        assertThat(value.value()).isEqualTo(25);
        assertThat(value.location().path()).isEqualTo("src/main/resources/application.properties");
        assertThat(value.location().line()).isEqualTo(1);
    }

    @Test
    void absentPropertyIsAbsent() {
        TestProject.in(root).resource(".", "application.yml", "server:\n  port: 8080\n");
        assertThat(load().getInt("spring.datasource.hikari.maximum-pool-size").isAbsent()).isTrue();
    }

    @Test
    void yamlLineNumbersPointAtTheValue() {
        TestProject.in(root).resource(".", "application.yml", """
                spring:
                  datasource:
                    hikari:
                      connection-timeout: 30000
                """);
        PropertyValue<Long> value = load().getLong("spring.datasource.hikari.connection-timeout");
        assertThat(value.value()).isEqualTo(30000L);
        assertThat(value.location().line()).isEqualTo(4);
    }

    @Test
    void propertiesWinOverYamlInTheSameLocation() {
        TestProject.in(root)
                .resource(".", "application.yml", "a:\n  b: yaml\n")
                .resource(".", "application.properties", "a.b=properties\n");
        assertThat(load().getString("a.b").value()).isEqualTo("properties");
    }

    @Test
    void configDirectoryWinsOverRoot() {
        TestProject.in(root)
                .resource(".", "application.properties", "a.b=root\n")
                .resource(".", "config/application.yml", "a:\n  b: config\n");
        assertThat(load().getString("a.b").value()).isEqualTo("config");
    }

    @Test
    void profileFileWinsOverBaseAndLaterProfileWins() {
        TestProject.in(root)
                .resource(".", "application.yml", "a:\n  b: base\n  c: base\n")
                .resource(".", "application-prod.yml", "a:\n  b: prod\n  c: prod\n")
                .resource(".", "application-eu.yml", "a:\n  c: eu\n");
        ResolvedConfig config = load("prod", "eu");
        assertThat(config.getString("a.b").value()).isEqualTo("prod");
        assertThat(config.getString("a.c").value()).isEqualTo("eu");
        assertThat(config.activeProfiles()).containsExactly("prod", "eu");
    }

    @Test
    void activeProfilesComeFromBaseFileWhenNotRequested() {
        TestProject.in(root)
                .resource(".", "application.yml", "spring:\n  profiles:\n    active: prod\na:\n  b: base\n")
                .resource(".", "application-prod.yml", "a:\n  b: prod\n");
        ResolvedConfig config = load();
        assertThat(config.activeProfiles()).containsExactly("prod");
        assertThat(config.getString("a.b").value()).isEqualTo("prod");
    }

    @Test
    void defaultProfileIsUsedWhenNothingIsActive() {
        TestProject.in(root)
                .resource(".", "application.yml", "a:\n  b: base\n")
                .resource(".", "application-default.yml", "a:\n  b: default\n");
        ResolvedConfig config = load();
        assertThat(config.activeProfiles()).containsExactly("default");
        assertThat(config.getString("a.b").value()).isEqualTo("default");
    }

    @Test
    void multiDocumentYamlHonorsProfileExpressions() {
        TestProject.in(root).resource(".", "application.yml", """
                a:
                  b: base
                ---
                spring:
                  config:
                    activate:
                      on-profile: "prod & !eu"
                a:
                  b: prod-not-eu
                ---
                spring:
                  config:
                    activate:
                      on-cloud-platform: kubernetes
                a:
                  b: k8s
                """);
        assertThat(load("prod").getString("a.b").value()).isEqualTo("prod-not-eu");
        assertThat(load("prod", "eu").getString("a.b").value()).isEqualTo("base");
        assertThat(load("dev").getString("a.b").value()).isEqualTo("base");
    }

    @Test
    void profileGroupsAndIncludeAreExpanded() {
        TestProject.in(root)
                .resource(".", "application.yml", """
                        spring:
                          profiles:
                            include: metrics
                            group:
                              prod: proddb
                        """)
                .resource(".", "application-proddb.yml", "a:\n  b: proddb\n");
        ResolvedConfig config = load("prod");
        assertThat(config.activeProfiles()).containsExactly("prod", "proddb", "metrics");
        assertThat(config.getString("a.b").value()).isEqualTo("proddb");
    }

    @Test
    void placeholdersWithDefaultsResolveAndWithoutDefaultsAreUnresolved() {
        TestProject.in(root).resource(".", "application.properties", """
                spring.datasource.hikari.maximum-pool-size=${DB_POOL:15}
                spring.datasource.hikari.connection-timeout=${DB_TIMEOUT}
                pool.copy=${spring.datasource.hikari.maximum-pool-size}
                """);
        ResolvedConfig config = load();
        assertThat(config.getInt("spring.datasource.hikari.maximum-pool-size").value()).isEqualTo(15);
        assertThat(config.getInt("pool.copy").value()).isEqualTo(15);
        PropertyValue<Long> timeout = config.getLong("spring.datasource.hikari.connection-timeout");
        assertThat(timeout.isUnresolved()).isTrue();
        assertThat(timeout.location().line()).isEqualTo(2);
        assertThat(diagnostics.toList()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.UNRESOLVED_VALUE);
            assertThat(d.message()).contains("spring.datasource.hikari.connection-timeout", "${DB_TIMEOUT}");
        });
    }

    @Test
    void unconvertibleValuesAreReportedNotGuessed() {
        TestProject.in(root).resource(".", "application.properties",
                "spring.datasource.hikari.connection-timeout=30s\n");
        PropertyValue<Long> timeout = load().getLong("spring.datasource.hikari.connection-timeout");
        assertThat(timeout.isUnresolved()).isTrue();
        assertThat(diagnostics.toList()).singleElement()
                .satisfies(d -> assertThat(d.code()).isEqualTo(Diagnostic.Code.INVALID_VALUE));
    }

    @Test
    void durationsUseSpringConversion() {
        TestProject.in(root).resource(".", "application.yml", "x:\n  wait: 2s\n  plain: 250\n");
        ResolvedConfig config = load();
        assertThat(config.getDuration("x.wait").value()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.getDuration("x.plain").value()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void childNamesListMapEntries() {
        TestProject.in(root).resource(".", "application.yml", """
                resilience4j:
                  retry:
                    instances:
                      backendA:
                        max-attempts: 3
                      backend-b:
                        maxAttempts: 2
                """);
        ResolvedConfig config = load();
        assertThat(config.childNames("resilience4j.retry.instances")).containsExactlyInAnyOrder("backendA", "backend-b");
        assertThat(config.getInt("resilience4j.retry.instances.backendA.max-attempts").value()).isEqualTo(3);
        assertThat(config.getInt("resilience4j.retry.instances.backend-b.max-attempts").value()).isEqualTo(2);
    }

    @Test
    void primaryLocationIsTheMostSpecificLoadedFile() {
        TestProject.in(root)
                .resource(".", "application.yml", "a: 1\n")
                .resource(".", "application-prod.yml", "a: 2\n")
                .resource(".", "application-dev.yml", "a: 3\n");
        ResolvedConfig config = load("prod");
        assertThat(config.primaryLocation()).hasValueSatisfying(
                l -> assertThat(l.path()).isEqualTo("src/main/resources/application-prod.yml"));
        assertThat(config.files()).filteredOn(ConfigFile::loaded).extracting(ConfigFile::profile)
                .containsExactly(null, "prod");
    }

    @Test
    void moduleWithoutConfigFilesHasNoPrimaryLocation() {
        assertThat(load().primaryLocation()).isEmpty();
    }
}
