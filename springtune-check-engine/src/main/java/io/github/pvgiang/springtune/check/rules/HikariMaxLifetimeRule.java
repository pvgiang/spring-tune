package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.settings.SpringTuneSettings;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** ST-HIKARI-003: the database (or a proxy) closes connections before HikariCP retires them. */
public final class HikariMaxLifetimeRule implements Rule {

    static final long DEFAULT_MARGIN_MS = 30_000;

    private final ModuleRuleSupport support = new ModuleRuleSupport("ST-HIKARI-003",
            "maxLifetime not shorter than database idle timeout", Severity.ERROR, "hikari",
            "HikariCP maxLifetime is not at least the margin shorter than the database's idle timeout"
                    + " declared in springtune.yml.");

    @Override
    public io.github.pvgiang.springtune.model.RuleDescriptor descriptor() {
        return support.descriptor();
    }

    @Override
    public List<Finding> evaluate(ProjectContext context) {
        List<Finding> findings = new ArrayList<>();
        long margin = context.options(support.descriptor().id()).getLong("margin-ms", DEFAULT_MARGIN_MS);
        for (ModuleModel module : context.modules()) {
            if (!module.usesHikari()) {
                continue;
            }
            Optional<SpringTuneSettings.Database> database = context.database(module);
            if (database.isEmpty() || database.get().idleTimeout() == null) {
                context.skipped(module, support.descriptor().id(),
                        "declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml");
                continue;
            }
            long idleMs = database.get().idleTimeout().toMillis();
            PropertyValue<Long> configured = module.config().getLong(Hikari.MAX_LIFETIME);
            if (configured.isUnresolved()) {
                continue;
            }
            long effective = Hikari.effectiveMaxLifetime(configured.orDefault(Hikari.DEFAULT_MAX_LIFETIME_MS));
            String fix = "Set " + Hikari.MAX_LIFETIME + " to at most " + Math.max(idleMs - margin, Hikari.MIN_MAX_LIFETIME_MS)
                    + " ms (database idle timeout " + idleMs + " ms minus a " + margin + " ms margin).";
            String why = "The database (or a proxy/firewall) silently closes connections idle longer than "
                    + idleMs + " ms. HikariCP then hands out dead connections, and requests fail with "
                    + "'connection reset' or 'communications link failure' errors.";
            String origin = configured.isAbsent() ? " (default)" : "";
            if (effective == 0) {
                support.add(findings, module, Hikari.MAX_LIFETIME,
                        "maxLifetime is 0, so connections never retire, but database '" + database.get().name()
                                + "' closes idle connections after " + idleMs + " ms.",
                        why, fix, module.locationOf(configured));
            } else if (effective > idleMs - margin) {
                String note = configured.isSet() && configured.value() != effective
                        ? " (configured " + configured.value() + " ms is below 30000 ms, so HikariCP uses the default)"
                        : origin;
                support.add(findings, module, Hikari.MAX_LIFETIME,
                        "maxLifetime is " + effective + " ms" + note + ", not at least " + margin
                                + " ms shorter than database '" + database.get().name() + "' idle timeout of "
                                + idleMs + " ms.",
                        why, fix, module.locationOf(configured));
            }
        }
        return findings;
    }
}
