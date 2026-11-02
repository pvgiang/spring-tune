package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** ST-HIKARI-001: threads wait too long for a pooled connection. */
public final class HikariConnectionTimeoutRule extends ModuleRule {

    static final long DEFAULT_MAX_MS = 5_000;

    private static final String WHY = "When the pool is exhausted, every request thread blocks for up to "
            + "connectionTimeout before failing. A long wait turns a brief database slowdown into a stalled "
            + "thread pool and timeouts in every upstream caller.";

    public HikariConnectionTimeoutRule() {
        super("ST-HIKARI-001", "Connection acquire timeout too long", Severity.WARNING, "hikari",
                "HikariCP connectionTimeout is left at the 30 s default, set above the threshold, or infinite.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesHikari()) {
            return;
        }
        long maxMs = options.getLong("max-ms", DEFAULT_MAX_MS);
        String fix = "Set " + Hikari.CONNECTION_TIMEOUT + " below your upstream request timeout, at most "
                + maxMs + " ms (e.g. 2000–5000).";
        PropertyValue<Long> timeout = module.config().getLong(Hikari.CONNECTION_TIMEOUT);
        if (timeout.isUnresolved()) {
            return;
        }
        if (timeout.isAbsent()) {
            findings.add(finding(module, Hikari.CONNECTION_TIMEOUT,
                    "connectionTimeout is not set, so HikariCP waits up to "
                            + Hikari.DEFAULT_CONNECTION_TIMEOUT_MS + " ms (default) for a connection.",
                    WHY, fix, module.fallbackLocation()));
        } else if (timeout.value() == 0) {
            findings.add(finding(module, Severity.ERROR, Hikari.CONNECTION_TIMEOUT,
                    "connectionTimeout is 0, which HikariCP treats as wait forever.", WHY, fix, timeout.location()));
        } else if (timeout.value() > maxMs) {
            findings.add(finding(module, Hikari.CONNECTION_TIMEOUT,
                    "connectionTimeout is " + timeout.value() + " ms, above the " + maxMs + " ms threshold.",
                    WHY, fix, timeout.location()));
        }
    }
}
