package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.Diagnostic;
import io.github.pvgiang.springtune.check.DiagnosticSink;
import io.github.pvgiang.springtune.check.deploy.ReplicaInfo;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.settings.SpringTuneSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Input to every rule: all modules plus user settings. */
public final class ProjectContext {

    private final Path root;
    private final List<ModuleModel> modules;
    private final SpringTuneSettings settings;
    private final DiagnosticSink diagnostics;
    private final Function<ModuleModel, Optional<ReplicaInfo>> replicas;

    public ProjectContext(Path root, List<ModuleModel> modules, SpringTuneSettings settings, DiagnosticSink diagnostics,
            Function<ModuleModel, Optional<ReplicaInfo>> replicas) {
        this.root = root;
        this.modules = List.copyOf(modules);
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.replicas = replicas;
    }

    public Path root() {
        return root;
    }

    public List<ModuleModel> modules() {
        return modules;
    }

    public SpringTuneSettings settings() {
        return settings;
    }

    public RuleOptions options(String ruleId) {
        Map<String, String> options = settings.ruleOverride(ruleId).options();
        return new RuleOptions(ruleId, options);
    }

    /** Settings entry for a module, if declared under {@code services}. */
    public Optional<SpringTuneSettings.Service> service(ModuleModel module) {
        return settings.service(module.path());
    }

    /**
     * Database a module uses: the explicit {@code services[].database}, or the only declared database when
     * exactly one exists and the module uses JDBC.
     */
    public Optional<SpringTuneSettings.Database> database(ModuleModel module) {
        Optional<String> explicit = service(module).map(SpringTuneSettings.Service::database);
        if (explicit.isPresent()) {
            return settings.databases().stream().filter(d -> d.name().equals(explicit.get())).findFirst();
        }
        return settings.databases().size() == 1 ? Optional.of(settings.databases().get(0)) : Optional.empty();
    }

    public Optional<ReplicaInfo> replicas(ModuleModel module) {
        return replicas.apply(module);
    }

    public void skipped(ModuleModel module, String ruleId, String reason) {
        diagnostics.add(module.path(), Diagnostic.Code.RULE_SKIPPED, ruleId + " not evaluated: " + reason);
    }
}
