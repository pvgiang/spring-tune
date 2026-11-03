package io.github.pvgiang.springtune.model;

import java.util.Locale;

/**
 * How bad a finding is. Ordered from most to least severe.
 *
 * <ul>
 *   <li>{@link #ERROR}: likely to cause an outage or data-path failure under normal production load.</li>
 *   <li>{@link #WARNING}: risky; fails or degrades under stress or partial outages.</li>
 *   <li>{@link #INFO}: best practice; low risk but worth knowing.</li>
 * </ul>
 */
public enum Severity {
    ERROR,
    WARNING,
    INFO;

    /** {@code true} if this severity is the same as or more severe than {@code threshold}. */
    public boolean isAtLeast(Severity threshold) {
        return ordinal() <= threshold.ordinal();
    }

    /** Case-insensitive parse; throws {@link IllegalArgumentException} with the allowed values. */
    public static Severity parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("Unknown severity '" + value + "'; expected one of ERROR, WARNING, INFO");
        }
    }
}
