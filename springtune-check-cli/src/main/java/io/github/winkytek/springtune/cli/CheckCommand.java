package io.github.winkytek.springtune.cli;

import io.github.winkytek.springtune.check.CheckEngine;
import io.github.winkytek.springtune.check.CheckException;
import io.github.winkytek.springtune.check.CheckReport;
import io.github.winkytek.springtune.check.CheckRequest;
import io.github.winkytek.springtune.check.FailThreshold;
import io.github.winkytek.springtune.check.report.ReportFormat;
import io.github.winkytek.springtune.check.report.Reporters;
import io.github.winkytek.springtune.check.settings.SpringTuneSettings;
import io.github.winkytek.springtune.model.Finding;
import io.github.winkytek.springtune.model.Severity;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "check", mixinStandardHelpOptions = true,
        description = "Analyze Spring Boot modules below ROOT and report risky configuration.")
final class CheckCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", defaultValue = ".", paramLabel = "ROOT",
            description = "Project root; report paths are relative to it (default: current directory).")
    Path root;

    @Option(names = {"-m", "--module"}, paramLabel = "DIR",
            description = "Module directory to analyze (repeatable). Default: discover all modules below ROOT.")
    List<Path> modules = new ArrayList<>();

    @Option(names = {"-p", "--profile"}, split = ",", paramLabel = "PROFILE",
            description = "Spring profile(s) to activate, e.g. --profile prod. Default: springtune.yml "
                    + "'profiles', else each module's spring.profiles.active.")
    List<String> profiles = new ArrayList<>();

    @Option(names = {"-c", "--config"}, paramLabel = "FILE",
            description = "springtune.yml to use. Default: ROOT/springtune.yml if present.")
    Path config;

    @Option(names = "--fail-on", paramLabel = "LEVEL",
            description = "Fail (exit 1) on findings at or above ERROR, WARNING or INFO; NONE never fails. "
                    + "Default: springtune.yml 'failOn', else ERROR.")
    String failOn;

    @Option(names = {"-f", "--format"}, split = ",", paramLabel = "FORMAT", defaultValue = "text",
            description = "Report files to write: text, markdown, json, sarif (comma-separated). Default: text.")
    List<String> formats;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR", defaultValue = "springtune-reports",
            description = "Directory for report files (default: ${DEFAULT-VALUE}, relative to the working directory).")
    Path output;

    @Option(names = {"-q", "--quiet"}, description = "Do not print the text report to stdout.")
    boolean quiet;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            List<ReportFormat> parsedFormats = formats.stream().map(ReportFormat::parse).distinct().toList();
            CheckRequest request = new CheckRequest(root, modules, profiles, config);
            CheckEngine engine = new CheckEngine();
            SpringTuneSettings settings = engine.settings(request);
            Severity threshold = FailThreshold.parse(failOn != null ? failOn : settings.failOn());
            CheckReport report = engine.run(request, settings);

            if (!quiet) {
                out.print(Reporters.render(report, ReportFormat.TEXT));
            }
            List<ReportFormat> files = parsedFormats.stream().filter(f -> f != ReportFormat.TEXT || quiet).toList();
            if (!files.isEmpty()) {
                for (Path written : Reporters.write(report, files, output)) {
                    out.println("Wrote " + written);
                }
            }
            List<Finding> blocking = report.blocking(threshold);
            if (!blocking.isEmpty()) {
                out.println();
                out.println("FAILED: " + blocking.size() + " finding(s) at or above " + threshold
                        + ". Fix them or suppress with a reason in springtune.yml.");
                out.flush();
                return SpringTuneCli.EXIT_FINDINGS;
            }
            out.flush();
            return SpringTuneCli.EXIT_OK;
        } catch (CheckException ex) {
            err.println("springtune: " + ex.getMessage());
            err.flush();
            return SpringTuneCli.EXIT_USAGE;
        }
    }
}
