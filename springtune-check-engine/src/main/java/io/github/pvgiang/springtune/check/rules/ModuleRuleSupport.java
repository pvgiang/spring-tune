package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Location;
import io.github.pvgiang.springtune.model.RuleDescriptor;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** Finding factory for rules that need the whole {@link ProjectContext} but still report per module. */
final class ModuleRuleSupport {

    private final RuleDescriptor descriptor;

    ModuleRuleSupport(String id, String title, Severity severity, String category, String summary) {
        this.descriptor = new RuleDescriptor(id, title, severity, category, summary);
    }

    RuleDescriptor descriptor() {
        return descriptor;
    }

    void add(List<Finding> findings, ModuleModel module, String subject, String message, String why, String fix,
            Location location) {
        findings.add(new Finding(descriptor.id(), descriptor.defaultSeverity(), module.path(), subject, message, why,
                fix, location, null));
    }
}
