package io.github.winkytek.springtune.check.config;

import io.github.winkytek.springtune.model.Location;
import java.util.Objects;

/**
 * Result of looking up one property in the effective configuration.
 *
 * <ul>
 *   <li>{@link State#SET}: present and converted; {@link #value()} is non-null.</li>
 *   <li>{@link State#ABSENT}: not configured; the library default applies.</li>
 *   <li>{@link State#UNRESOLVED}: present but unknowable statically (placeholder without default, or not
 *       convertible); rules must not evaluate it.</li>
 * </ul>
 */
public record PropertyValue<T>(String key, State state, T value, String raw, Location location) {

    public enum State {
        SET,
        ABSENT,
        UNRESOLVED
    }

    public PropertyValue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        if (state == State.SET) {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(location, "location");
        }
    }

    static <T> PropertyValue<T> absent(String key) {
        return new PropertyValue<>(key, State.ABSENT, null, null, null);
    }

    public boolean isSet() {
        return state == State.SET;
    }

    public boolean isAbsent() {
        return state == State.ABSENT;
    }

    public boolean isUnresolved() {
        return state == State.UNRESOLVED;
    }

    /** Value if set, {@code defaultValue} if absent. Must not be called when unresolved. */
    public T orDefault(T defaultValue) {
        if (state == State.UNRESOLVED) {
            throw new IllegalStateException(key + " is unresolved; check isUnresolved() first");
        }
        return state == State.SET ? value : defaultValue;
    }
}
