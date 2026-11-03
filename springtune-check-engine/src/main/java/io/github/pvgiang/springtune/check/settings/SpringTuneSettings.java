package io.github.pvgiang.springtune.check.settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Typed form of {@code springtune.yml}. Every field is optional; see {@code docs/configuration.md}.
 *
 * @param failOn    severity threshold that fails the build ({@code ERROR}, {@code WARNING}, {@code INFO}, {@code NONE})
 * @param profiles  Spring profiles to analyze when none are given on the command line
 * @param databases databases with their connection limits (inputs for budget and lifetime rules)
 * @param services  per-module deployment facts
 * @param rules     per-rule overrides keyed by rule id
 * @param suppress  accepted findings, each with a mandatory reason
 */
public record SpringTuneSettings(
        String failOn,
        List<String> profiles,
        List<Database> databases,
        List<Service> services,
        Map<String, RuleOverride> rules,
        List<SuppressionEntry> suppress) {

    public SpringTuneSettings {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        databases = databases == null ? List.of() : List.copyOf(databases);
        services = services == null ? List.of() : List.copyOf(services);
        Map<String, RuleOverride> normalized = new LinkedHashMap<>();
        if (rules != null) {
            rules.forEach((id, override) -> normalized.put(id.toUpperCase(Locale.ROOT), override));
        }
        rules = Map.copyOf(normalized);
        suppress = suppress == null ? List.of() : List.copyOf(suppress);
    }

    public static SpringTuneSettings empty() {
        return new SpringTuneSettings(null, null, null, null, null, null);
    }

    /** The {@code services} entry for a module path ({@code "."}, {@code "orders"}, ...), if declared. */
    public java.util.Optional<Service> service(String modulePath) {
        return services.stream().filter(s -> s.path() != null && normalizePath(s.path()).equals(modulePath)).findFirst();
    }

    static String normalizePath(String path) {
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./") && p.length() > 2) {
            p = p.substring(2);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "." : p;
    }

    public RuleOverride ruleOverride(String ruleId) {
        return rules.getOrDefault(ruleId, RuleOverride.NONE);
    }

    /** Returns human-readable problems; empty when valid. */
    public List<String> validate(Set<String> knownRuleIds) {
        List<String> errors = new ArrayList<>();
        if (failOn != null && !Set.of("ERROR", "WARNING", "INFO", "NONE").contains(failOn.toUpperCase(Locale.ROOT))) {
            errors.add("failOn must be one of ERROR, WARNING, INFO, NONE but was '" + failOn + "'");
        }
        Set<String> databaseNames = new java.util.HashSet<>();
        for (int i = 0; i < databases.size(); i++) {
            Database db = databases.get(i);
            String at = "databases[" + i + "]";
            if (db.name() == null || db.name().isBlank()) {
                errors.add(at + ".name is required");
            } else if (!databaseNames.add(db.name())) {
                errors.add(at + ".name '" + db.name() + "' is declared twice");
            }
            if (db.maxConnections() == null || db.maxConnections() < 1) {
                errors.add(at + ".maxConnections is required and must be >= 1");
            }
            if (db.safetyFactor() != null && !(db.safetyFactor() > 0 && db.safetyFactor() <= 1)) {
                errors.add(at + ".safetyFactor must be in (0, 1]");
            }
            if (db.idleTimeout() != null && (db.idleTimeout().isNegative() || db.idleTimeout().isZero())) {
                errors.add(at + ".idleTimeout must be positive");
            }
        }
        for (int i = 0; i < services.size(); i++) {
            Service service = services.get(i);
            String at = "services[" + i + "]";
            if (service.path() == null || service.path().isBlank()) {
                errors.add(at + ".path is required (module directory relative to the root, '.' for the root)");
            }
            if (service.database() != null && !databaseNames.contains(service.database())) {
                errors.add(at + ".database '" + service.database() + "' is not declared under databases");
            }
            if (service.maxReplicas() != null && service.maxReplicas() < 1) {
                errors.add(at + ".maxReplicas must be >= 1");
            }
        }
        rules.forEach((id, override) -> {
            if (!knownRuleIds.contains(id)) {
                errors.add("rules." + id + " refers to an unknown rule id");
            }
            if (override != null && override.severity() != null
                    && !Set.of("ERROR", "WARNING", "INFO").contains(override.severity().toUpperCase(Locale.ROOT))) {
                errors.add("rules." + id + ".severity must be ERROR, WARNING or INFO");
            }
        });
        for (int i = 0; i < suppress.size(); i++) {
            SuppressionEntry entry = suppress.get(i);
            String at = "suppress[" + i + "]";
            if (entry.rule() == null || !knownRuleIds.contains(entry.rule().toUpperCase(Locale.ROOT))) {
                errors.add(at + ".rule must be a known rule id but was '" + entry.rule() + "'");
            }
            if (entry.reason() == null || entry.reason().isBlank()) {
                errors.add(at + ".reason is required: explain why this finding is acceptable");
            }
        }
        return errors;
    }

    /**
     * @param name           identifier referenced by {@link Service#database()}
     * @param maxConnections database {@code max_connections}
     * @param idleTimeout    server-side idle connection timeout (e.g. MySQL {@code wait_timeout}), optional
     * @param safetyFactor   share of {@code maxConnections} pools may use; default 0.8
     */
    public record Database(String name, Integer maxConnections, Duration idleTimeout, Double safetyFactor) {
    }

    /**
     * @param path        module directory relative to the root ({@code "."} for the root)
     * @param database    name of the database this service uses
     * @param maxReplicas maximum number of instances (HPA max or fixed replicas)
     * @param deployment  Helm values or Kubernetes manifests to read replicas from, relative to the root
     */
    public record Service(String path, String database, Integer maxReplicas, List<String> deployment) {
        public Service {
            deployment = deployment == null ? List.of() : List.copyOf(deployment);
        }
    }

    /**
     * @param enabled  {@code false} disables the rule
     * @param severity overrides the severity of every finding of the rule
     * @param options  rule-specific options (see each rule's docs page)
     */
    public record RuleOverride(Boolean enabled, String severity, Map<String, String> options) {
        static final RuleOverride NONE = new RuleOverride(null, null, null);

        public RuleOverride {
            Map<String, String> normalized = new LinkedHashMap<>();
            if (options != null) {
                options.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
            }
            options = Map.copyOf(normalized);
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    /**
     * @param rule    rule id to suppress
     * @param module  limit to one module path (optional)
     * @param subject limit to one property key or code symbol (optional)
     * @param reason  why the team accepts the finding (required)
     */
    public record SuppressionEntry(String rule, String module, String subject, String reason) {
    }
}
