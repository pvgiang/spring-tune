package io.github.winky.springtune.gradle;

import io.github.winky.springtune.check.CheckEngine;
import io.github.winky.springtune.check.CheckException;
import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.CheckRequest;
import io.github.winky.springtune.check.Diagnostic;
import io.github.winky.springtune.check.FailThreshold;
import io.github.winky.springtune.check.ResolvedBuild;
import io.github.winky.springtune.check.project.ProjectDiscovery;
import io.github.winky.springtune.check.report.ReportFormat;
import io.github.winky.springtune.check.report.Reporters;
import io.github.winky.springtune.check.settings.SpringTuneSettings;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/**
 * Runs inside the isolated worker class loader, the only class of the plugin that touches the engine.
 * Mirrors the Maven plugin's {@code CheckMojo}.
 */
public abstract class CheckWorkAction implements WorkAction<CheckWorkAction.Parameters> {

    private static final Logger LOG = Logging.getLogger(CheckWorkAction.class);

    public interface Parameters extends WorkParameters {
        DirectoryProperty getModuleDirectory();

        DirectoryProperty getRootDirectory();

        ListProperty<String> getProfiles();

        Property<String> getFailOn();

        ListProperty<String> getFormats();

        RegularFileProperty getConfigFile();

        DirectoryProperty getOutputDirectory();

        SetProperty<String> getResolvedArtifacts();

        Property<String> getSpringBootVersion();

        ListProperty<String> getUnresolvedDependencies();

        Property<String> getProjectPath();
    }

    @Override
    public void execute() {
        Parameters p = getParameters();
        Path module = p.getModuleDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (!ProjectDiscovery.isModule(module)) {
            LOG.info("SpringTune: no Spring Boot configuration or @SpringBootApplication in {}; skipping", module);
            return;
        }
        Path root = p.getRootDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (!module.startsWith(root)) {
            root = module;
        }
        try {
            ResolvedBuild resolved = new ResolvedBuild(p.getResolvedArtifacts().get(),
                    p.getSpringBootVersion().getOrNull());
            List<String> unresolved = p.getUnresolvedDependencies().getOrElse(List.of());
            if (!unresolved.isEmpty()) {
                LOG.warn("SpringTune: {} runtime dependencies could not be resolved ({}); library detection uses"
                        + " their requested names only, so their transitive libraries are unknown and rules for"
                        + " them may not run", unresolved.size(), String.join(", ", unresolved));
            }
            LOG.info("SpringTune: resolved Spring Boot version {}, {} module(s) on the runtime classpath",
                    resolved.springBootVersion(), resolved.artifactIds().size());
            CheckRequest request = new CheckRequest(root, List.of(module), p.getProfiles().get(),
                    p.getConfigFile().isPresent() ? p.getConfigFile().get().getAsFile().toPath() : null,
                    resolved.artifactIds().isEmpty() && resolved.springBootVersion() == null
                            ? Map.of() : Map.of(module, resolved));
            CheckEngine engine = new CheckEngine();
            SpringTuneSettings settings = engine.settings(request);
            Severity threshold = FailThreshold.parse(p.getFailOn().getOrElse(settings.failOn()));
            CheckReport report = engine.run(request, settings);
            log(report);
            List<ReportFormat> formats = p.getFormats().get().stream().map(ReportFormat::parse).distinct().toList();
            for (Path written : Reporters.write(report, formats, p.getOutputDirectory().get().getAsFile().toPath())) {
                LOG.lifecycle("SpringTune report: {}", written.toUri());
            }
            List<Finding> blocking = report.blocking(threshold);
            if (!blocking.isEmpty()) {
                throw new GradleException("SpringTune: " + blocking.size() + " finding(s) at or above " + threshold
                        + " in " + p.getProjectPath().get() + ". Fix them or suppress them with a reason in"
                        + " springtune.yml (or run with -Pspringtune.failOn=NONE).");
            }
        } catch (CheckException ex) {
            throw new GradleException("SpringTune: " + ex.getMessage(), ex);
        }
    }

    private static void log(CheckReport report) {
        LOG.lifecycle("{} {}: {} finding(s), {} suppressed", report.tool(), report.version(),
                report.findings().size(), report.suppressedCount());
        for (Finding finding : report.findings()) {
            if (finding.isSuppressed()) {
                continue;
            }
            String line = finding.severity() + " " + finding.ruleId() + " " + finding.location() + ": "
                    + finding.message() + " Fix: " + finding.fix();
            switch (finding.severity()) {
                case ERROR -> LOG.error(line);
                case WARNING -> LOG.warn(line);
                case INFO -> LOG.lifecycle(line);
            }
        }
        for (Diagnostic diagnostic : report.diagnostics()) {
            LOG.lifecycle("Not evaluated: {}", diagnostic.message());
        }
    }
}
