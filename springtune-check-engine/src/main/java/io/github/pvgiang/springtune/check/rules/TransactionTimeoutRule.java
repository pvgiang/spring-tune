package io.github.pvgiang.springtune.check.rules;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.source.JavaSources;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** ST-TX-001: nothing bounds how long a transaction or query may run. */
public final class TransactionTimeoutRule extends ModuleRule {

    static final List<String> TIMEOUT_KEYS = List.of(
            "spring.transaction.default-timeout",
            "spring.jpa.properties.jakarta.persistence.query.timeout",
            "spring.jpa.properties.javax.persistence.query.timeout",
            "spring.jdbc.template.query-timeout");

    public TransactionTimeoutRule() {
        super("ST-TX-001", "No transaction or query timeout", Severity.INFO, "transaction",
                "None of the transaction/query timeout properties is set and no @Transactional(timeout=...) exists.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesHikari()) {
            return;
        }
        for (String key : TIMEOUT_KEYS) {
            if (!module.config().getString(key).isAbsent()) {
                return;
            }
        }
        if (hasTransactionalTimeout(module.sources())) {
            return;
        }
        findings.add(finding(module, "spring.transaction.default-timeout",
                "No transaction or query timeout is configured: a slow query holds its pooled connection for as "
                        + "long as the database lets it run.",
                "Long-running queries keep connections checked out. A handful of them is enough to drain the pool, "
                        + "and then every other request waits in connectionTimeout.",
                "Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout "
                        + "(spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).",
                module.fallbackLocation()));
    }

    private static boolean hasTransactionalTimeout(JavaSources sources) {
        for (JavaSources.ParsedSource source : sources.all()) {
            boolean found = source.unit().findFirst(AnnotationExpr.class,
                    a -> a.getName().getIdentifier().equals("Transactional") && a instanceof NormalAnnotationExpr n
                            && n.getPairs().stream().anyMatch(p -> p.getNameAsString().equals("timeout")))
                    .isPresent();
            if (found) {
                return true;
            }
        }
        return false;
    }
}
