package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** ST-HIKARI-002: connection leaks go unnoticed until the pool is empty. */
public final class HikariLeakDetectionRule extends ModuleRule {

    private static final String WHY = "A connection that is borrowed and never returned shrinks the pool "
            + "permanently. Without leak detection the first symptom is pool exhaustion in production, with no "
            + "stack trace pointing at the code that leaked.";
    private static final String FIX = "Set " + Hikari.LEAK_DETECTION_THRESHOLD + " slightly above your longest "
            + "legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.";

    public HikariLeakDetectionRule() {
        super("ST-HIKARI-002", "Connection leak detection disabled", Severity.WARNING, "hikari",
                "HikariCP leakDetectionThreshold is unset, 0, or a value HikariCP silently ignores.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesHikari()) {
            return;
        }
        PropertyValue<Long> threshold = module.config().getLong(Hikari.LEAK_DETECTION_THRESHOLD);
        PropertyValue<Long> maxLifetime = module.config().getLong(Hikari.MAX_LIFETIME);
        if (threshold.isUnresolved()) {
            return;
        }
        if (threshold.isAbsent()) {
            findings.add(finding(module, Hikari.LEAK_DETECTION_THRESHOLD,
                    "leakDetectionThreshold is not set (HikariCP default 0 = disabled).", WHY, FIX,
                    module.fallbackLocation()));
            return;
        }
        long value = threshold.value();
        if (value == 0) {
            findings.add(finding(module, Hikari.LEAK_DETECTION_THRESHOLD,
                    "leakDetectionThreshold is 0 (disabled).", WHY, FIX, threshold.location()));
        } else if (value < Hikari.MIN_LEAK_DETECTION_MS) {
            findings.add(finding(module, Hikari.LEAK_DETECTION_THRESHOLD,
                    "leakDetectionThreshold is " + value + " ms; HikariCP disables leak detection below "
                            + Hikari.MIN_LEAK_DETECTION_MS + " ms.", WHY, FIX, threshold.location()));
        } else if (!maxLifetime.isUnresolved()) {
            long lifetime = Hikari.effectiveMaxLifetime(maxLifetime.orDefault(Hikari.DEFAULT_MAX_LIFETIME_MS));
            if (lifetime > 0 && value > lifetime) {
                findings.add(finding(module, Hikari.LEAK_DETECTION_THRESHOLD,
                        "leakDetectionThreshold (" + value + " ms) is above maxLifetime (" + lifetime
                                + " ms); HikariCP disables leak detection in that case.",
                        WHY, FIX, threshold.location()));
            }
        }
    }
}
