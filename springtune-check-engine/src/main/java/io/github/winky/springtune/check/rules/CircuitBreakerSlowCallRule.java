package io.github.winky.springtune.check.rules;

import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.config.ResolvedConfig;
import io.github.winky.springtune.check.project.ModuleModel;
import io.github.winky.springtune.model.Finding;
import io.github.winky.springtune.model.Severity;
import java.util.List;

/** ST-R4J-002: a circuit breaker that only reacts to errors never opens for a hanging dependency. */
public final class CircuitBreakerSlowCallRule extends ModuleRule {

    static final double DEFAULT_SLOW_CALL_RATE = 100.0;

    public CircuitBreakerSlowCallRule() {
        super("ST-R4J-002", "Circuit breaker blind to slow calls", Severity.WARNING, "resilience4j",
                "A circuit breaker instance has no time limiter and no effective slow-call detection"
                        + " (slow-call-duration-threshold set and slow-call-rate-threshold below 100).");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        ResolvedConfig config = module.config();
        String prefix = "resilience4j.circuitbreaker.instances";
        boolean defaultTimeLimiter = config.hasAnyUnder("resilience4j.timelimiter.configs.default");
        for (String instance : config.childNames(prefix)) {
            if (defaultTimeLimiter || config.hasAnyUnder("resilience4j.timelimiter.instances." + instance)) {
                continue;
            }
            PropertyValue<String> duration = Resilience4j.effective(config, "circuitbreaker", instance,
                    "slow-call-duration-threshold", String.class);
            PropertyValue<Double> rate = Resilience4j.effective(config, "circuitbreaker", instance,
                    "slow-call-rate-threshold", Double.class);
            if (duration.isUnresolved() || rate.isUnresolved()) {
                continue;
            }
            if (duration.isSet() && rate.orDefault(DEFAULT_SLOW_CALL_RATE) < DEFAULT_SLOW_CALL_RATE) {
                continue;
            }
            findings.add(finding(module, prefix + "." + instance,
                    "Circuit breaker '" + instance + "' has no time limiter, and slow-call detection is off"
                            + (duration.isSet() ? " (slow-call-rate-threshold is 100%)"
                                    : " (default slow-call-duration-threshold 60 s at 100% rate)") + ".",
                    "Calls that hang are neither failures nor 'slow' by these settings, so the breaker stays closed "
                            + "while every caller thread waits on the dependency. It only protects against fast "
                            + "failures, the case that needs protection least.",
                    "Set slow-call-duration-threshold (e.g. 2s) and slow-call-rate-threshold (e.g. 50) for '" + instance
                            + "', and/or add resilience4j.timelimiter.instances." + instance + ".timeout-duration "
                            + "(TimeLimiter applies to async/reactive calls; sync calls still need client timeouts).",
                    config.locationUnder(prefix + "." + instance).orElse(module.fallbackLocation())));
        }
    }
}
