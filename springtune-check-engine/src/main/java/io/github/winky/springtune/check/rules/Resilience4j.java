package io.github.winky.springtune.check.rules;

import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.config.ResolvedConfig;

/**
 * Resolves a Resilience4j Spring Boot property the way the library does: instance value, else the
 * instance's {@code base-config}, else {@code configs.default}.
 */
final class Resilience4j {

    private Resilience4j() {
    }

    static <T> PropertyValue<T> effective(ResolvedConfig config, String module, String instance, String key,
            Class<T> type) {
        String root = "resilience4j." + module;
        PropertyValue<T> own = config.get(root + ".instances." + instance + "." + key, type);
        if (!own.isAbsent()) {
            return own;
        }
        PropertyValue<String> base = config.getString(root + ".instances." + instance + ".base-config");
        String baseName = base.isSet() ? base.value() : "default";
        PropertyValue<T> inherited = config.get(root + ".configs." + baseName + "." + key, type);
        if (!inherited.isAbsent() || baseName.equals("default")) {
            return inherited;
        }
        return config.get(root + ".configs.default." + key, type);
    }
}
