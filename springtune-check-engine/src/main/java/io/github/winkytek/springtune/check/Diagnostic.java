package io.github.winkytek.springtune.check;

import java.util.Objects;

/**
 * Something the check could not evaluate, and why. Diagnostics never fail the build; they make the
 * limits of the analysis visible instead of silently guessing.
 *
 * @param module  module path, or {@code "."}
 * @param code    machine-readable reason, see {@link Code}
 * @param message human explanation including what input would resolve it
 */
public record Diagnostic(String module, Code code, String message) {

    public Diagnostic {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public enum Code {
        /** A value uses a {@code ${...}} placeholder with no default; it is only known at runtime. */
        UNRESOLVED_VALUE,
        /** A value could not be converted to the type Spring would bind (e.g. {@code "30s"} for a millisecond long). */
        INVALID_VALUE,
        /** A rule needs input that is not available (e.g. database limits, replica count). */
        RULE_SKIPPED,
        /** A source or manifest file could not be parsed. */
        PARSE_ERROR,
        /** A configuration construct the check does not model (e.g. legacy {@code spring.profiles} documents). */
        UNSUPPORTED
    }
}
