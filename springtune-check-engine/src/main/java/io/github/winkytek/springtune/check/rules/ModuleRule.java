package io.github.winkytek.springtune.check.rules;

import io.github.winkytek.springtune.check.project.ModuleModel;
import io.github.winkytek.springtune.model.Finding;
import io.github.winkytek.springtune.model.Location;
import io.github.winkytek.springtune.model.RuleDescriptor;
import io.github.winkytek.springtune.model.Severity;
import java.util.ArrayList;
import java.util.List;

/** Base class for rules that look at one module at a time. */
public abstract class ModuleRule implements Rule {

    private final RuleDescriptor descriptor;

    protected ModuleRule(String id, String title, Severity severity, String category, String summary) {
        this.descriptor = new RuleDescriptor(id, title, severity, category, summary);
    }

    @Override
    public final RuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public final List<Finding> evaluate(ProjectContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ModuleModel module : context.modules()) {
            evaluate(module, context.options(descriptor.id()), findings);
        }
        return findings;
    }

    protected abstract void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings);

    protected Finding finding(ModuleModel module, String subject, String message, String why, String fix,
            Location location) {
        return finding(module, descriptor.defaultSeverity(), subject, message, why, fix, location);
    }

    protected Finding finding(ModuleModel module, Severity severity, String subject, String message, String why,
            String fix, Location location) {
        return new Finding(descriptor.id(), severity, module.path(), subject, message, why, fix, location, null);
    }
}
