package io.github.pvgiang.springtune.check;

import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.RuleDescriptor;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/**
 * Result of a check run. Deliberately contains no timestamps or absolute paths so reports are
 * reproducible and can be compared in golden-file tests.
 */
public record CheckReport(
        String tool,
        String version,
        List<ModuleSummary> modules,
        List<RuleDescriptor> rules,
        List<Finding> findings,
        List<Diagnostic> diagnostics) {

    public CheckReport {
        modules = List.copyOf(modules);
        rules = List.copyOf(rules);
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Unsuppressed findings at or above {@code threshold}; {@code null} threshold means never fail. */
    public List<Finding> blocking(Severity threshold) {
        if (threshold == null) {
            return List.of();
        }
        return findings.stream().filter(f -> !f.isSuppressed() && f.severity().isAtLeast(threshold)).toList();
    }

    public long count(Severity severity) {
        return findings.stream().filter(f -> !f.isSuppressed() && f.severity() == severity).count();
    }

    public long suppressedCount() {
        return findings.stream().filter(Finding::isSuppressed).count();
    }

    /**
     * @param path              module path relative to the root
     * @param springBootVersion version read from the build file, or {@code null}
     * @param activeProfiles    profiles the configuration was resolved for
     * @param configFiles       config files that contributed, in precedence order (lowest first)
     * @param technologies      detected technologies that decide which rules apply
     */
    public record ModuleSummary(String path, String springBootVersion, List<String> activeProfiles,
            List<String> configFiles, List<String> technologies) {
    }
}
