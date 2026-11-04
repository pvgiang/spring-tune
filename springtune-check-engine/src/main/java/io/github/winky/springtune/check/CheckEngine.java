package io.github.winky.springtune.check;

import io.github.winky.springtune.check.config.ConfigFile;
import io.github.winky.springtune.check.config.ProjectConfigLoader;
import io.github.winky.springtune.check.config.ResolvedConfig;
import io.github.winky.springtune.check.deploy.DeploymentScanner;
import io.github.winky.springtune.check.deploy.ReplicaInfo;
import io.github.winky.springtune.check.project.BuildInfo;
import io.github.winky.springtune.check.project.BuildInspector;
import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.check.project.ProjectDiscovery;
import io.github.winky.springtune.check.project.SourceGraph;
import io.github.winky.springtune.check.rules.ProjectContext;
import io.github.winky.springtune.check.rules.Rule;
import io.github.winky.springtune.check.settings.SettingsLoader;
import io.github.winky.springtune.check.settings.SpringTuneSettings;
import io.github.winky.springtune.check.source.JavaSources;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.RuleDescriptor;
import io.github.winky.springtune.model.Severity;
import io.github.winky.springtune.model.Suppression;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Runs all enabled rules over a project. Entry point shared by the CLI, the Maven plugin and the Gradle plugin.
 *
 * <pre>{@code
 * CheckReport report = new CheckEngine().run(CheckRequest.of(Path.of(".")));
 * }</pre>
 */
public final class CheckEngine {

    public static final String TOOL = "SpringTune";

    private final List<Rule> rules;

    /** Uses every rule registered through {@link ServiceLoader} on the engine's class loader. */
    public CheckEngine() {
        this(loadRules(CheckEngine.class.getClassLoader()));
    }

    public CheckEngine(List<Rule> rules) {
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(r -> r.descriptor().id()));
        Map<String, Rule> unique = new LinkedHashMap<>();
        for (Rule rule : sorted) {
            if (unique.put(rule.descriptor().id(), rule) != null) {
                throw new IllegalStateException("Duplicate rule id " + rule.descriptor().id());
            }
        }
        this.rules = List.copyOf(unique.values());
    }

    public static List<Rule> loadRules(ClassLoader classLoader) {
        List<Rule> loaded = new ArrayList<>();
        ServiceLoader.load(Rule.class, classLoader).forEach(loaded::add);
        return loaded;
    }

    public List<RuleDescriptor> descriptors() {
        return rules.stream().map(Rule::descriptor).toList();
    }

    public static String version() {
        try (InputStream in = CheckEngine.class.getResourceAsStream("/springtune-version.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            return properties.getProperty("version", "unknown");
        } catch (IOException ex) {
            return "unknown";
        }
    }

    /** Loads {@code springtune.yml} for the request (validated against the known rule ids). */
    public SpringTuneSettings settings(CheckRequest request) {
        return SettingsLoader.load(request.configFile(), request.root(),
                rules.stream().map(r -> r.descriptor().id()).collect(Collectors.toSet()));
    }

    public CheckReport run(CheckRequest request) {
        return run(request, settings(request));
    }

    public CheckReport run(CheckRequest request, SpringTuneSettings settings) {
        Path root = request.root();
        DiagnosticSink diagnostics = new DiagnosticSink();
        List<Path> moduleDirs = request.modules().isEmpty()
                ? new ProjectDiscovery().discover(root)
                : request.modules().stream().map(p -> p.toAbsolutePath().normalize()).toList();
        for (Path dir : moduleDirs) {
            if (!Files.isDirectory(dir)) {
                throw new CheckException("Module directory not found: " + dir);
            }
        }
        List<String> profiles = !request.profiles().isEmpty() ? request.profiles() : settings.profiles();

        ProjectConfigLoader configLoader = new ProjectConfigLoader();
        BuildInspector buildInspector = new BuildInspector();
        SourceGraph sourceGraph = new SourceGraph(root, new ProjectDiscovery().sourceDirs(root), buildInspector,
                diagnostics);
        List<ModuleModel> modules = new ArrayList<>();
        for (Path dir : moduleDirs) {
            String path = PathUtils.relative(root, dir);
            ResolvedConfig config = configLoader.load(root, dir, profiles, diagnostics);
            BuildInfo declared = buildInspector.inspect(root, dir);
            BuildInfo build = request.resolvedBuild(dir).map(declared::merge).orElse(declared);
            JavaSources sources = JavaSources.parse(root, dir, path, diagnostics);
            ModuleModel module = new ModuleModel(path, dir, build, config, sources,
                    sourceGraph.librariesOf(dir, build));
            if (!module.configFilesKnown()) {
                diagnostics.add(path, Diagnostic.Code.UNSUPPORTED, "Local config is incomplete: "
                        + module.configUnknownReason().orElseThrow()
                        + "; rules that report missing properties were not evaluated for this module");
            } else if (module.usesHikari() && module.definesDataSourceInCode()) {
                diagnostics.add(path, Diagnostic.Code.RULE_SKIPPED, "HikariCP rules not evaluated: the module builds"
                        + " its own DataSource in code, so spring.datasource.hikari.* may not apply to it");
            }
            modules.add(module);
        }

        DeploymentScanner deploymentScanner = new DeploymentScanner();
        Map<String, Optional<ReplicaInfo>> replicaCache = new HashMap<>();
        ProjectContext context = new ProjectContext(root, modules, settings, diagnostics,
                module -> replicaCache.computeIfAbsent(module.path(), p -> deploymentScanner.resolve(root,
                        module.directory(), p, settings.service(p).orElse(null), diagnostics)));

        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            SpringTuneSettings.RuleOverride override = settings.ruleOverride(rule.descriptor().id());
            if (!override.isEnabled()) {
                continue;
            }
            Severity severityOverride = override.severity() == null ? null : Severity.parse(override.severity());
            for (Finding finding : rule.evaluate(context)) {
                Finding effective = severityOverride == null ? finding : finding.withSeverity(severityOverride);
                findings.add(suppress(effective, settings));
            }
        }
        findings.sort(Comparator.comparing(Finding::module)
                .thenComparing(f -> f.location().path())
                .thenComparingInt(f -> f.location().line())
                .thenComparing(Finding::ruleId)
                .thenComparing(Finding::subject));

        List<CheckReport.ModuleSummary> summaries = modules.stream().map(CheckEngine::summarize).toList();
        return new CheckReport(TOOL, version(), summaries, descriptors(), findings, diagnostics.toList());
    }

    private static Finding suppress(Finding finding, SpringTuneSettings settings) {
        for (SpringTuneSettings.SuppressionEntry entry : settings.suppress()) {
            boolean rule = entry.rule().toUpperCase(Locale.ROOT).equals(finding.ruleId());
            boolean module = entry.module() == null || entry.module().equals(finding.module());
            boolean subject = entry.subject() == null || entry.subject().equals(finding.subject());
            if (rule && module && subject) {
                return finding.withSuppression(new Suppression(entry.reason()));
            }
        }
        return finding;
    }

    private static CheckReport.ModuleSummary summarize(ModuleModel module) {
        List<String> technologies = new ArrayList<>();
        if (module.usesHikari()) {
            technologies.add("hikari");
        }
        if (module.usesTomcat()) {
            technologies.add("tomcat");
        }
        if (module.usesVirtualThreads()) {
            technologies.add("virtual-threads");
        }
        if (module.sources().findAnnotation("EnableAsync").isPresent()) {
            technologies.add("async");
        }
        if (module.usesFeign()) {
            technologies.add("openfeign");
        }
        if (module.config().hasAnyUnder("resilience4j")) {
            technologies.add("resilience4j");
        }
        return new CheckReport.ModuleSummary(module.path(), module.build().springBootVersion(),
                module.config().activeProfiles(),
                module.config().files().stream().filter(ConfigFile::loaded).map(ConfigFile::relative).toList(),
                technologies);
    }
}
