package io.github.winkytek.springtune.check.rules;

import io.github.winkytek.springtune.model.Finding;
import io.github.winkytek.springtune.model.RuleDescriptor;
import java.util.List;

/**
 * A static check. Implementations are discovered with {@link java.util.ServiceLoader}; register custom
 * rules in {@code META-INF/services/io.github.winkytek.springtune.check.rules.Rule}.
 *
 * <p>Contract: a rule must stay silent rather than guess. When an input it needs is unknown it either
 * returns nothing or records a {@link io.github.winkytek.springtune.check.Diagnostic} via the context.
 */
public interface Rule {

    RuleDescriptor descriptor();

    List<Finding> evaluate(ProjectContext context);
}
