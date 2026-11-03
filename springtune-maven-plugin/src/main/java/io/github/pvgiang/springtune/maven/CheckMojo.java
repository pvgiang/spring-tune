package io.github.pvgiang.springtune.maven;

import io.github.pvgiang.springtune.check.CheckEngine;
import io.github.pvgiang.springtune.check.CheckException;
import io.github.pvgiang.springtune.check.CheckReport;
import io.github.pvgiang.springtune.check.CheckRequest;
import io.github.pvgiang.springtune.check.Diagnostic;
import io.github.pvgiang.springtune.check.FailThreshold;
import io.github.pvgiang.springtune.check.project.ProjectDiscovery;
import io.github.pvgiang.springtune.check.report.ReportFormat;
import io.github.pvgiang.springtune.check.report.Reporters;
import io.github.pvgiang.springtune.check.settings.SpringTuneSettings;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Analyzes the current module's Spring Boot configuration, sources and deployment manifests and fails the
 * build when findings reach {@code failOn}. Report paths are relative to {@code rootDirectory} (the
 * multi-module root by default) so SARIF uploads line up with the repository.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    /** Module to analyze. */
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    File basedir;

    /** Root for relative report paths and the default {@code springtune.yml} location. */
    @Parameter(property = "springtune.rootDirectory", defaultValue = "${maven.multiModuleProjectDirectory}")
    File rootDirectory;

    /** Spring profiles to activate (comma-separated on the command line). Default: springtune.yml, else spring.profiles.active. */
    @Parameter(property = "springtune.profiles")
    List<String> profiles;

    /** ERROR, WARNING, INFO or NONE. Default: springtune.yml {@code failOn}, else ERROR. */
    @Parameter(property = "springtune.failOn")
    String failOn;

    /** Report files to write: text, markdown, json, sarif. */
    @Parameter(property = "springtune.formats", defaultValue = "markdown,sarif")
    List<String> formats;

    @Parameter(property = "springtune.outputDirectory", defaultValue = "${project.build.directory}/springtune")
    File outputDirectory;

    /** Explicit springtune.yml; default {@code <rootDirectory>/springtune.yml} if it exists. */
    @Parameter(property = "springtune.configFile")
    File configFile;

    @Parameter(property = "springtune.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("SpringTune check skipped (springtune.skip=true)");
            return;
        }
        Path module = basedir.toPath().toAbsolutePath().normalize();
        if (!ProjectDiscovery.isModule(module)) {
            getLog().info("No Spring Boot configuration or @SpringBootApplication in " + module + "; skipping");
            return;
        }
        Path root = (rootDirectory != null ? rootDirectory.toPath() : module).toAbsolutePath().normalize();
        if (!module.startsWith(root)) {
            root = module;
        }
        try {
            CheckRequest request = new CheckRequest(root, List.of(module), profiles,
                    configFile != null ? configFile.toPath() : null);
            CheckEngine engine = new CheckEngine();
            SpringTuneSettings settings = engine.settings(request);
            Severity threshold = FailThreshold.parse(failOn != null ? failOn : settings.failOn());
            CheckReport report = engine.run(request, settings);
            log(report);
            List<ReportFormat> parsed = formats.stream().map(ReportFormat::parse).distinct().toList();
            for (Path written : Reporters.write(report, parsed, outputDirectory.toPath())) {
                getLog().info("Report: " + written);
            }
            List<Finding> blocking = report.blocking(threshold);
            if (!blocking.isEmpty()) {
                throw new MojoFailureException("SpringTune: " + blocking.size() + " finding(s) at or above "
                        + threshold + ". Fix them or suppress them with a reason in springtune.yml"
                        + " (or set -Dspringtune.failOn=NONE).");
            }
        } catch (CheckException ex) {
            throw new MojoExecutionException("SpringTune: " + ex.getMessage(), ex);
        }
    }

    private void log(CheckReport report) {
        getLog().info(report.tool() + " " + report.version() + ": " + report.findings().size() + " finding(s), "
                + report.suppressedCount() + " suppressed");
        for (Finding finding : report.findings()) {
            if (finding.isSuppressed()) {
                continue;
            }
            String line = finding.ruleId() + " " + finding.location() + ": " + finding.message() + " Fix: "
                    + finding.fix();
            switch (finding.severity()) {
                case ERROR -> getLog().error(line);
                case WARNING -> getLog().warn(line);
                case INFO -> getLog().info(line);
            }
        }
        for (Diagnostic diagnostic : report.diagnostics()) {
            getLog().info("Not evaluated: " + diagnostic.message());
        }
    }
}
