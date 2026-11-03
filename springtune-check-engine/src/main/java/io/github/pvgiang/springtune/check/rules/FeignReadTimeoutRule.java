package io.github.pvgiang.springtune.check.rules;

import io.github.pvgiang.springtune.check.config.ResolvedConfig;
import io.github.pvgiang.springtune.check.project.ModuleModel;
import io.github.pvgiang.springtune.check.source.JavaSources;
import io.github.pvgiang.springtune.model.Finding;
import io.github.pvgiang.springtune.model.Severity;
import java.util.List;

/** ST-HTTP-002: OpenFeign clients rely on the 60 s default read timeout. */
public final class FeignReadTimeoutRule extends ModuleRule {

    static final List<String> PREFIXES = List.of("spring.cloud.openfeign.client.config", "feign.client.config");

    public FeignReadTimeoutRule() {
        super("ST-HTTP-002", "Feign client on default 60 s read timeout", Severity.WARNING, "http",
                "OpenFeign is used but no client config sets read-timeout.");
    }

    @Override
    protected void evaluate(ModuleModel module, RuleOptions options, List<Finding> findings) {
        if (!module.usesFeign() || hasReadTimeout(module.config())) {
            return;
        }
        findings.add(finding(module, "spring.cloud.openfeign.client.config.default.read-timeout",
                "OpenFeign is used but no read-timeout is configured; clients wait up to 60000 ms (default) for a "
                        + "response, with a 10000 ms connect timeout.",
                "A 60 s wait per call holds a request thread for a minute whenever a dependency hangs. A few slow "
                        + "calls per second exhaust the thread pool well before the timeout fires.",
                "Set spring.cloud.openfeign.client.config.default.read-timeout (and connect-timeout) to a value that "
                        + "fits your latency budget, overriding per client where needed.",
                module.sources().findAnnotation("EnableFeignClients").map(JavaSources.Found::location)
                        .orElse(module.fallbackLocation())));
    }

    private static boolean hasReadTimeout(ResolvedConfig config) {
        for (String prefix : PREFIXES) {
            for (String client : config.childNames(prefix)) {
                if (!config.getString(prefix + "." + client + ".read-timeout").isAbsent()) {
                    return true;
                }
            }
        }
        return false;
    }
}
