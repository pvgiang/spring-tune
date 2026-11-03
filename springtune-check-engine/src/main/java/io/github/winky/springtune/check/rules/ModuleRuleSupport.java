package io.github.winky.springtune.check.rules;

import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Location;
import io.github.winky.springtune.model.RuleDescriptor;
import io.github.winky.springtune.model.Severity;
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
