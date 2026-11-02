package io.github.pvgiang.springtune.check;

import io.github.pvgiang.springtune.model.Severity;
import java.util.Locale;

/** Parses {@code --fail-on}/{@code failOn}: a severity, or {@code NONE} (returned as {@code null}). */
public final class FailThreshold {

    private FailThreshold() {
    }

    public static Severity parse(String value) {
        if (value == null || value.isBlank()) {
            return Severity.ERROR;
        }
        if (value.trim().toUpperCase(Locale.ROOT).equals("NONE")) {
            return null;
        }
        try {
            return Severity.parse(value);
        } catch (IllegalArgumentException ex) {
            throw new CheckException("fail-on must be ERROR, WARNING, INFO or NONE but was '" + value + "'");
        }
    }
}
