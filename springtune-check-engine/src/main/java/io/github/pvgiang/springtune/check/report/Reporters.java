package io.github.pvgiang.springtune.check.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.pvgiang.springtune.check.CheckException;
import io.github.pvgiang.springtune.check.CheckReport;
import io.github.pvgiang.springtune.check.Diagnostic;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.RuleDescriptor;
import io.github.pvgiang.springtune.model.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a {@link CheckReport}. All renderers are deterministic for a given report. */
public final class Reporters {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Reporters() {
    }

    public static String render(CheckReport report, ReportFormat format) {
        return switch (format) {
            case TEXT -> text(report);
            case MARKDOWN -> markdown(report);
            case JSON -> toJson(json(report));
            case SARIF -> toJson(sarif(report));
        };
    }

    /** Writes each format to {@code directory/<format file name>} and returns the written paths. */
    public static List<Path> write(CheckReport report, List<ReportFormat> formats, Path directory) {
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            for (ReportFormat format : formats) {
                Path file = directory.resolve(format.fileName());
                Files.writeString(file, render(report, format));
                written.add(file);
            }
        } catch (IOException ex) {
            throw new CheckException("Cannot write reports to " + directory + ": " + ex.getMessage(), ex);
        }
        return written;
    }

    // ---------------------------------------------------------------- text

    static String text(CheckReport report) {
        StringBuilder out = new StringBuilder();
        out.append(report.tool()).append(' ').append(report.version()).append(": ").append(summaryLine(report))
                .append("\n");
        for (Finding finding : report.findings()) {
            if (finding.isSuppressed()) {
                continue;
            }
            out.append('\n').append(String.format("%-7s %s  %s", finding.severity(), finding.ruleId(),
                    finding.location())).append('\n');
            out.append("        ").append(finding.message()).append('\n');
            out.append("        Fix: ").append(finding.fix()).append('\n');
            out.append("        Docs: ").append(RuleDescriptor.DOCS_BASE).append(finding.ruleId()).append(".md\n");
        }
        if (report.suppressedCount() > 0) {
            out.append("\nSuppressed (").append(report.suppressedCount()).append("):\n");
            report.findings().stream().filter(Finding::isSuppressed).forEach(f -> out.append("  - ")
                    .append(f.ruleId()).append(' ').append(f.location()).append(": ")
                    .append(f.suppression().reason()).append('\n'));
        }
        if (!report.diagnostics().isEmpty()) {
            out.append("\nNot evaluated (").append(report.diagnostics().size()).append("):\n");
            for (Diagnostic diagnostic : report.diagnostics()) {
                out.append("  - [").append(diagnostic.module()).append("] ").append(diagnostic.message()).append('\n');
            }
        }
        return out.toString();
    }

    static String summaryLine(CheckReport report) {
        long total = report.findings().size() - report.suppressedCount();
        return report.modules().size() + " module(s), " + total + " finding(s) (" + report.count(Severity.ERROR)
                + " error, " + report.count(Severity.WARNING) + " warning, " + report.count(Severity.INFO) + " info), "
                + report.suppressedCount() + " suppressed";
    }

    // ---------------------------------------------------------------- markdown

    static String markdown(CheckReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(report.tool()).append(" report\n\n");
        md.append("| Errors | Warnings | Info | Suppressed | Not evaluated |\n|---:|---:|---:|---:|---:|\n");
        md.append("| ").append(report.count(Severity.ERROR)).append(" | ").append(report.count(Severity.WARNING))
                .append(" | ").append(report.count(Severity.INFO)).append(" | ").append(report.suppressedCount())
                .append(" | ").append(report.diagnostics().size()).append(" |\n\n");
        md.append("## Modules\n\n| Module | Spring Boot | Profiles | Technologies |\n|---|---|---|---|\n");
        for (CheckReport.ModuleSummary module : report.modules()) {
            md.append("| `").append(module.path()).append("` | ")
                    .append(module.springBootVersion() == null ? "?" : module.springBootVersion()).append(" | ")
                    .append(String.join(", ", module.activeProfiles())).append(" | ")
                    .append(String.join(", ", module.technologies())).append(" |\n");
        }
        md.append("\n## Findings\n");
        boolean any = false;
        for (Finding finding : report.findings()) {
            if (finding.isSuppressed()) {
                continue;
            }
            any = true;
            md.append("\n### ").append(icon(finding.severity())).append(' ').append(finding.severity()).append(" · [")
                    .append(finding.ruleId()).append("](").append(RuleDescriptor.DOCS_BASE).append(finding.ruleId())
                    .append(".md) · `").append(finding.location()).append("`\n\n");
            md.append(finding.message()).append("\n\n");
            md.append("- **Why:** ").append(finding.why()).append('\n');
            md.append("- **Fix:** ").append(finding.fix()).append('\n');
        }
        if (!any) {
            md.append("\nNo findings.\n");
        }
        if (report.suppressedCount() > 0) {
            md.append("\n## Suppressed\n\n| Rule | Location | Reason |\n|---|---|---|\n");
            report.findings().stream().filter(Finding::isSuppressed).forEach(f -> md.append("| ").append(f.ruleId())
                    .append(" | `").append(f.location()).append("` | ").append(escape(f.suppression().reason()))
                    .append(" |\n"));
        }
        if (!report.diagnostics().isEmpty()) {
            md.append("\n## Not evaluated\n\nThese checks need more input. They are not failures.\n\n");
            for (Diagnostic diagnostic : report.diagnostics()) {
                md.append("- `").append(diagnostic.module()).append("` ").append(diagnostic.code()).append(": ")
                        .append(diagnostic.message()).append('\n');
            }
        }
        return md.toString();
    }

    private static String icon(Severity severity) {
        return switch (severity) {
            case ERROR -> "🔴";
            case WARNING -> "🟠";
            case INFO -> "🔵";
        };
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    // ---------------------------------------------------------------- json

    static Map<String, Object> json(CheckReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", report.tool());
        root.put("version", report.version());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("error", report.count(Severity.ERROR));
        summary.put("warning", report.count(Severity.WARNING));
        summary.put("info", report.count(Severity.INFO));
        summary.put("suppressed", report.suppressedCount());
        summary.put("notEvaluated", report.diagnostics().size());
        root.put("summary", summary);
        List<Object> modules = new ArrayList<>();
        for (CheckReport.ModuleSummary module : report.modules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", module.path());
            m.put("springBootVersion", module.springBootVersion());
            m.put("activeProfiles", module.activeProfiles());
            m.put("configFiles", module.configFiles());
            m.put("technologies", module.technologies());
            modules.add(m);
        }
        root.put("modules", modules);
        List<Object> findings = new ArrayList<>();
        for (Finding finding : report.findings()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("ruleId", finding.ruleId());
            f.put("severity", finding.severity().name());
            f.put("module", finding.module());
            f.put("subject", finding.subject());
            f.put("location", ordered("path", finding.location().path(), "line", finding.location().line(),
                    "column", finding.location().column()));
            f.put("message", finding.message());
            f.put("why", finding.why());
            f.put("fix", finding.fix());
            f.put("suppressed", finding.isSuppressed());
            if (finding.isSuppressed()) {
                f.put("suppressionReason", finding.suppression().reason());
            }
            findings.add(f);
        }
        root.put("findings", findings);
        List<Object> diagnostics = new ArrayList<>();
        for (Diagnostic diagnostic : report.diagnostics()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("module", diagnostic.module());
            d.put("code", diagnostic.code().name());
            d.put("message", diagnostic.message());
            diagnostics.add(d);
        }
        root.put("notEvaluated", diagnostics);
        return root;
    }

    // ---------------------------------------------------------------- sarif 2.1.0

    static Map<String, Object> sarif(CheckReport report) {
        List<RuleDescriptor> rules = report.rules();
        List<Object> sarifRules = new ArrayList<>();
        Map<String, Integer> ruleIndex = new LinkedHashMap<>();
        for (RuleDescriptor rule : rules) {
            ruleIndex.put(rule.id(), ruleIndex.size());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rule.id());
            r.put("name", rule.title().replaceAll("[^A-Za-z0-9]+", " ").trim().replace(' ', '-'));
            r.put("shortDescription", ordered("text", rule.title()));
            r.put("fullDescription", ordered("text", rule.summary()));
            r.put("helpUri", rule.docsUrl());
            r.put("help", ordered("text", rule.summary() + " See " + rule.docsUrl()));
            r.put("defaultConfiguration", ordered("level", level(rule.defaultSeverity())));
            r.put("properties", ordered("category", rule.category(), "tags", List.of("spring-boot", rule.category())));
            sarifRules.add(r);
        }
        List<Object> results = new ArrayList<>();
        for (Finding finding : report.findings()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", finding.ruleId());
            if (ruleIndex.containsKey(finding.ruleId())) {
                result.put("ruleIndex", ruleIndex.get(finding.ruleId()));
            }
            result.put("level", level(finding.severity()));
            result.put("message", ordered("text", finding.message() + "\n\nWhy: " + finding.why() + "\n\nFix: "
                    + finding.fix()));
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("startLine", finding.location().line());
            if (finding.location().column() > 0) {
                region.put("startColumn", finding.location().column());
            }
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("uri", finding.location().path());
            artifact.put("uriBaseId", "%SRCROOT%");
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", artifact);
            physical.put("region", region);
            result.put("locations", List.of(ordered("physicalLocation", physical)));
            result.put("partialFingerprints", ordered("springtune/v1", finding.fingerprint()));
            if (finding.isSuppressed()) {
                result.put("suppressions", List.of(ordered("kind", "external",
                        "justification", finding.suppression().reason())));
            }
            result.put("properties", ordered("module", finding.module(), "subject", finding.subject()));
            results.add(result);
        }
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", report.tool());
        driver.put("version", report.version());
        driver.put("informationUri", "https://github.com/pvgiang/spring-tune");
        driver.put("rules", sarifRules);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", ordered("driver", driver));
        run.put("results", results);
        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return sarif;
    }

    static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    /** Insertion-ordered map: {@code Map.of} iteration order varies between JVM runs. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String toJson(Object tree) {
        try {
            return JSON.writeValueAsString(tree) + "\n";
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
