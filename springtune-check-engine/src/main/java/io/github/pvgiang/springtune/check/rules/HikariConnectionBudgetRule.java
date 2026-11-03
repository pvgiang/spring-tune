package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.deploy.ReplicaInfo;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.settings.SpringTuneSettings;
import io.github.pvgiang.springtune.model.ConnectionBudget;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.RuleDescriptor;
import io.github.pvgiang.springtune.model.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ST-HIKARI-004: the pools of every replica of every service sharing a database can open more
 * connections than the database allows. Uses {@link ConnectionBudget}, the same formula the runtime
 * starter uses to cap its recommendations.
 */
public final class HikariConnectionBudgetRule implements Rule {

    private final ModuleRuleSupport support = new ModuleRuleSupport("ST-HIKARI-004",
            "Connection budget exceeded", Severity.ERROR, "hikari",
            "maximumPoolSize x maxReplicas summed over the services sharing a database exceeds"
                    + " max_connections x safetyFactor.");

    @Override
    public RuleDescriptor descriptor() {
        return support.descriptor();
    }

    private record Member(ModuleModel module, PropertyValue<Integer> poolSize, int pool, ReplicaInfo replicas) {
    }

    @Override
    public List<Finding> evaluate(ProjectContext context) {
        String id = support.descriptor().id();
        Map<String, List<Member>> byDatabase = new LinkedHashMap<>();
        java.util.Set<String> unevaluable = new java.util.HashSet<>();
        for (ModuleModel module : context.modules()) {
            if (!module.usesHikari()) {
                continue;
            }
            Optional<SpringTuneSettings.Database> database = context.database(module);
            if (database.isEmpty()) {
                context.skipped(module, id, context.settings().databases().isEmpty()
                        ? "declare databases[].maxConnections in springtune.yml"
                        : "map this module to a database with services[].database in springtune.yml");
                continue;
            }
            String name = database.get().name();
            PropertyValue<Integer> poolSize = module.config().getInt(Hikari.MAXIMUM_POOL_SIZE);
            Optional<ReplicaInfo> replicas = context.replicas(module);
            if (poolSize.isUnresolved()) {
                unevaluable.add(name);
                context.skipped(module, id, "maximum-pool-size is only known at runtime; database '" + name
                        + "' budget cannot be computed");
                continue;
            }
            if (replicas.isEmpty()) {
                unevaluable.add(name);
                context.skipped(module, id, "max replicas unknown; set services[].maxReplicas or"
                        + " services[].deployment in springtune.yml (database '" + name + "')");
                continue;
            }
            int pool = poolSize.orDefault(Hikari.DEFAULT_MAXIMUM_POOL_SIZE);
            byDatabase.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Member(module, poolSize, pool, replicas.get()));
        }

        List<Finding> findings = new ArrayList<>();
        for (SpringTuneSettings.Database database : context.settings().databases()) {
            List<Member> members = byDatabase.getOrDefault(database.name(), List.of());
            if (members.isEmpty() || unevaluable.contains(database.name())) {
                continue;
            }
            double factor = database.safetyFactor() != null ? database.safetyFactor()
                    : ConnectionBudget.DEFAULT_SAFETY_FACTOR;
            ConnectionBudget budget = new ConnectionBudget(database.maxConnections(), factor);
            ConnectionBudget.Evaluation evaluation = budget.evaluate(members.stream()
                    .map(m -> new ConnectionBudget.Consumer(m.module().path(), m.pool(), m.replicas().maxReplicas()))
                    .toList());
            if (!evaluation.exceeded()) {
                continue;
            }
            StringBuilder breakdown = new StringBuilder();
            for (Member member : members) {
                if (!breakdown.isEmpty()) {
                    breakdown.append(", ");
                }
                breakdown.append(member.module().path()).append(' ').append(member.pool()).append('x')
                        .append(member.replicas().maxReplicas()).append(" [replicas from ")
                        .append(member.replicas().source()).append(']');
            }
            String message = "Database '" + database.name() + "' allows " + budget.usableConnections()
                    + " pool connections (max_connections " + database.maxConnections() + " x safety factor "
                    + factor + ") but its services can open " + evaluation.demand() + ": " + breakdown + ".";
            String why = "At peak scale (or during a rolling update) the pools try to open more connections than "
                    + "the database accepts. New pods fail to start or requests fail with 'too many connections', "
                    + "usually exactly when traffic is highest.";
            for (Member member : members) {
                int cap = Math.max(1, budget.usableConnections() * member.pool() / (int) evaluation.demand());
                String fix = "Lower maximum-pool-size (proportional share for this service: " + cap
                        + "), cap maxReplicas, or raise max_connections / add a connection proxy such as PgBouncer.";
                support.add(findings, member.module(), Hikari.MAXIMUM_POOL_SIZE, message, why, fix,
                        member.module().locationOf(member.poolSize()));
            }
        }
        return findings;
    }
}
