package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** ST-HIKARI-005: very large pools add contention instead of throughput. */
public final class HikariPoolSizeRule extends ModuleRule {

    static final long DEFAULT_MAX = 50;

    public HikariPoolSizeRule() {
        super("ST-HIKARI-005", "Oversized connection pool", Severity.WARNING, "hikari",
                "HikariCP maximumPoolSize per instance is above the threshold.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesHikari()) {
            return;
        }
        long max = options.getLong("max", DEFAULT_MAX);
        PropertyValue<Integer> size = module.config().getInt(Hikari.MAXIMUM_POOL_SIZE);
        if (size.isSet() && size.value() > max) {
            findings.add(finding(module, Hikari.MAXIMUM_POOL_SIZE,
                    "maximumPoolSize is " + size.value() + " per instance, above the " + max + " threshold.",
                    "A database executes only about (cores × 2) queries in parallel efficiently. Extra connections "
                            + "queue inside the database instead of the pool, which raises latency for everyone, "
                            + "and they multiply by the replica count against max_connections.",
                    "Size the pool from measured concurrency (see the HikariCP 'About Pool Sizing' guide), "
                            + "typically 10–30, and check ST-HIKARI-004 for the fleet-wide budget.",
                    size.location()));
        }
    }
}
