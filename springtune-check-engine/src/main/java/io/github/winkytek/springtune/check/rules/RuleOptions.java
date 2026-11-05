package io.github.winkytek.springtune.check.rules;

import io.github.winkytek.springtune.check.CheckException;
import java.util.Locale;
import java.util.Map;

/** Typed access to {@code rules.<id>.options} from {@code springtune.yml}. */
public final class RuleOptions {

    private final String ruleId;
    private final Map<String, String> values;

    public RuleOptions(String ruleId, Map<String, String> values) {
        this.ruleId = ruleId;
        this.values = Map.copyOf(values);
    }

    public long getLong(String key, long defaultValue) {
        String value = values.get(key.toLowerCase(Locale.ROOT));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            throw new CheckException("rules." + ruleId + ".options." + key + " must be a whole number but was '"
                    + value + "'");
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = values.get(key.toLowerCase(Locale.ROOT));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            throw new CheckException("rules." + ruleId + ".options." + key + " must be a number but was '"
                    + value + "'");
        }
    }
}
