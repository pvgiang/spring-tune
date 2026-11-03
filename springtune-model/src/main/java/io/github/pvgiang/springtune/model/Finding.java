package io.github.pvgiang.springtune.model;

import java.util.Objects;

/**
 * A problem found by a static rule.
 *
 * @param ruleId      rule identifier, see {@link RuleDescriptor#id()}
 * @param severity    effective severity (after configuration overrides)
 * @param module      module path relative to the analysis root ({@code "."} for the root)
 * @param subject     what the finding is about: a property key or a code symbol; used for fingerprints and suppression matching
 * @param message     what is wrong, with concrete values
 * @param why         why it matters (the incident it leads to)
 * @param fix         what to change
 * @param location    where to look
 * @param suppression non-null when the team accepted this finding
 */
public record Finding(
        String ruleId,
        Severity severity,
        String module,
        String subject,
        String message,
        String why,
        String fix,
        Location location,
        Suppression suppression) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(fix, "fix");
        Objects.requireNonNull(location, "location");
    }

    public boolean isSuppressed() {
        return suppression != null;
    }

    public Finding withSeverity(Severity newSeverity) {
        return new Finding(ruleId, newSeverity, module, subject, message, why, fix, location, suppression);
    }

    public Finding withSuppression(Suppression newSuppression) {
        return new Finding(ruleId, severity, module, subject, message, why, fix, location, newSuppression);
    }

    /** Stable identity across runs (independent of line numbers), used by SARIF consumers to track a finding. */
    public String fingerprint() {
        return Integer.toHexString(Objects.hash(ruleId, module, subject, location.path()));
    }
}
