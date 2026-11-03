package io.github.pvgiang.springtune.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CheckCommandTest {

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cli = SpringTuneCli.commandLine();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute(args);
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void riskyApp() throws IOException {
        write("pom.xml", "<project><dependencies><dependency><artifactId>spring-boot-starter-jdbc</artifactId>"
                + "</dependency></dependencies></project>");
        write("src/main/resources/application.yml", "spring.datasource.hikari.connection-timeout: 0\n");
    }

    @Test
    void exitsOneWhenFindingsReachThreshold() throws IOException {
        riskyApp();
        assertThat(run("check", root.toString())).isEqualTo(SpringTuneCli.EXIT_FINDINGS);
        assertThat(out.toString()).contains("ERROR   ST-HIKARI-001", "FAILED: 1 finding(s) at or above ERROR");
    }

    @Test
    void exitsZeroWhenBelowThresholdOrNone() throws IOException {
        riskyApp();
        assertThat(run("check", root.toString(), "--fail-on", "NONE")).isEqualTo(SpringTuneCli.EXIT_OK);
        write("src/main/resources/application.yml", "spring.datasource.hikari.connection-timeout: 2000\n");
        assertThat(run("check", root.toString())).isEqualTo(SpringTuneCli.EXIT_OK);
    }

    @Test
    void warningThresholdFailsOnWarnings() throws IOException {
        riskyApp();
        write("src/main/resources/application.yml", "spring.datasource.hikari.connection-timeout: 2000\n");
        assertThat(run("check", root.toString(), "--fail-on", "warning")).isEqualTo(SpringTuneCli.EXIT_FINDINGS);
    }

    @Test
    void failOnFromSettingsFileIsUsed() throws IOException {
        riskyApp();
        write("springtune.yml", "failOn: NONE\n");
        assertThat(run("check", root.toString())).isEqualTo(SpringTuneCli.EXIT_OK);
    }

    @Test
    void writesRequestedReportFiles() throws IOException {
        riskyApp();
        Path output = root.resolve("reports");
        run("check", root.toString(), "--format", "sarif,markdown,json", "--output", output.toString(), "--quiet");
        assertThat(output.resolve("springtune-report.sarif")).content().contains("\"version\" : \"2.1.0\"");
        assertThat(output.resolve("springtune-report.md")).content().contains("ST-HIKARI-001");
        assertThat(output.resolve("springtune-report.json")).content().contains("\"ruleId\" : \"ST-HIKARI-001\"");
        assertThat(out.toString()).doesNotContain("ERROR   ST-HIKARI-001");
    }

    @Test
    void invalidInputExitsTwo() throws IOException {
        riskyApp();
        assertThat(run("check", root.toString(), "--fail-on", "sometimes")).isEqualTo(SpringTuneCli.EXIT_USAGE);
        assertThat(err.toString()).contains("fail-on must be");
        assertThat(run("check", root.toString(), "--format", "pdf")).isEqualTo(SpringTuneCli.EXIT_USAGE);
        assertThat(run("check", root.resolve("missing").toString())).isEqualTo(SpringTuneCli.EXIT_USAGE);
        write("springtune.yml", "suppress:\n  - rule: ST-HIKARI-001\n");
        assertThat(run("check", root.toString())).isEqualTo(SpringTuneCli.EXIT_USAGE);
        assertThat(run("check", "--no-such-option")).isEqualTo(SpringTuneCli.EXIT_USAGE);
    }

    @Test
    void rulesCommandListsAllRules() {
        assertThat(run("rules")).isZero();
        assertThat(out.toString()).contains("ST-HIKARI-004", "ST-R4J-002", "docs/rules/ST-TX-001.md");
    }
}
