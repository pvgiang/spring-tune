package io.github.winky.springtune.check.rules;

import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Severity;
import java.util.List;

/** ST-R4J-001: fixed-interval retries synchronize clients and multiply load on a struggling dependency. */
public final class RetryBackoffRule extends ModuleRule {

    static final int DEFAULT_MAX_ATTEMPTS = 3;

    public RetryBackoffRule() {
        super("ST-R4J-001", "Retry without exponential backoff or jitter", Severity.WARNING, "resilience4j",
                "A Resilience4j retry instance retries (max-attempts > 1) with neither exponential backoff nor"
                        + " randomized wait.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        String prefix = "resilience4j.retry.instances";
        for (String instance : module.config().childNames(prefix)) {
            PropertyValue<Integer> attempts =
                    Resilience4j.effective(module.config(), "retry", instance, "max-attempts", Integer.class);
            PropertyValue<Boolean> exponential = Resilience4j.effective(module.config(), "retry", instance,
                    "enable-exponential-backoff", Boolean.class);
            PropertyValue<Boolean> randomized = Resilience4j.effective(module.config(), "retry", instance,
                    "enable-randomized-wait", Boolean.class);
            if (attempts.isUnresolved() || exponential.isUnresolved() || randomized.isUnresolved()) {
                continue;
            }
            int maxAttempts = attempts.orDefault(DEFAULT_MAX_ATTEMPTS);
            if (maxAttempts <= 1 || exponential.orDefault(false) || randomized.orDefault(false)) {
                continue;
            }
            findings.add(finding(module, prefix + "." + instance,
                    "Retry '" + instance + "' makes up to " + maxAttempts + " attempts at a fixed interval (no "
                            + "exponential backoff, no randomized wait).",
                    "When the dependency degrades, every client retries in lock-step at the same interval. Each "
                            + "failure becomes " + maxAttempts + " requests, which multiplies load at the worst "
                            + "moment and delays recovery (retry storm).",
                    "Set enable-exponential-backoff: true (with exponential-backoff-multiplier) and/or "
                            + "enable-randomized-wait: true for '" + instance + "', and retry only idempotent calls.",
                    module.config().locationUnder(prefix + "." + instance).orElse(module.fallbackLocation())));
        }
    }
}
