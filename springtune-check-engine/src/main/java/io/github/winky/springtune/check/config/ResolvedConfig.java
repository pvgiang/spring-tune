package io.github.winky.springtune.check.config;

import io.github.winky.springtune.check.Diagnostic;
import io.github.winky.springtune.check.DiagnosticSink;
import io.github.winky.springtune.check.PathUtils;
import io.github.winky.springtune.model.Location;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.MutablePropertySources;

/**
 * The effective Spring configuration of one module for a set of active profiles, with origins.
 * Lookups use Spring Boot's {@link Binder}, so relaxed names, placeholders with defaults and type
 * conversion (including {@code Duration} and {@code DataSize}) behave exactly as at runtime.
 */
public final class ResolvedConfig {

    private final String module;
    private final Path root;
    private final List<String> activeProfiles;
    private final List<ConfigFile> files;
    private final Iterable<ConfigurationPropertySource> sources;
    private final Binder binder;
    private final DiagnosticSink diagnostics;

    ResolvedConfig(String module, Path root, List<String> activeProfiles, List<ConfigFile> files,
            MutablePropertySources propertySources, DiagnosticSink diagnostics) {
        this.module = module;
        this.root = root;
        this.activeProfiles = List.copyOf(activeProfiles);
        this.files = List.copyOf(files);
        this.sources = ConfigurationPropertySources.from(propertySources);
        this.binder = new Binder(sources, new PropertySourcesPlaceholdersResolver(propertySources));
        this.diagnostics = diagnostics;
    }

    public List<String> activeProfiles() {
        return activeProfiles;
    }

    /** All discovered config files, including those not loaded for the active profiles. */
    public List<ConfigFile> files() {
        return files;
    }

    /**
     * Most specific loaded file: where a missing property should be added. Empty when the module has
     * no configuration files at all.
     */
    public Optional<Location> primaryLocation() {
        ConfigFile last = null;
        for (ConfigFile file : files) {
            if (file.loaded()) {
                last = file;
            }
        }
        return Optional.ofNullable(last).map(f -> Location.fileLevel(f.relative()));
    }

    public PropertyValue<Long> getLong(String key) {
        return get(key, Long.class);
    }

    public PropertyValue<Integer> getInt(String key) {
        return get(key, Integer.class);
    }

    public PropertyValue<Boolean> getBoolean(String key) {
        return get(key, Boolean.class);
    }

    public PropertyValue<String> getString(String key) {
        return get(key, String.class);
    }

    public PropertyValue<java.time.Duration> getDuration(String key) {
        return get(key, java.time.Duration.class);
    }

    /**
     * Looks up {@code key} (any relaxed form) and converts it like Spring Boot would. Unresolvable or
     * unconvertible values are reported once as diagnostics and returned as {@link PropertyValue.State#UNRESOLVED}.
     */
    public <T> PropertyValue<T> get(String key, Class<T> type) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
        ConfigurationProperty property = find(name);
        if (property == null) {
            return PropertyValue.absent(key);
        }
        String raw = String.valueOf(property.getValue());
        Location location = toLocation(property.getOrigin()).orElse(null);
        try {
            BindResult<T> result = binder.bind(name, Bindable.of(type));
            if (!result.isBound()) {
                return PropertyValue.absent(key);
            }
            T value = result.get();
            if (value instanceof String text && text.contains("${")) {
                return unresolved(key, raw, location, Diagnostic.Code.UNRESOLVED_VALUE,
                        "has no default and is only known at runtime");
            }
            return new PropertyValue<>(key, PropertyValue.State.SET, value, raw,
                    location != null ? location : primaryLocation().orElse(Location.fileLevel(module)));
        } catch (BindException ex) {
            boolean placeholder = raw.contains("${");
            return unresolved(key, raw, location,
                    placeholder ? Diagnostic.Code.UNRESOLVED_VALUE : Diagnostic.Code.INVALID_VALUE,
                    placeholder ? "has no default and is only known at runtime"
                            : "cannot be converted to " + type.getSimpleName() + " as Spring Boot would bind it");
        }
    }

    /** {@code true} if any property exists at or below {@code prefix}. */
    public boolean hasAnyUnder(String prefix) {
        ConfigurationPropertyName name = ConfigurationPropertyName.adapt(prefix, '.');
        for (ConfigurationPropertySource source : sources) {
            if (source instanceof IterableConfigurationPropertySource iterable
                    && iterable.stream().anyMatch(n -> n.equals(name) || name.isAncestorOf(n))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names of the map entries directly below {@code prefix}, e.g. instance names below
     * {@code resilience4j.retry.instances}. Original spelling of the first occurrence is kept.
     */
    public List<String> childNames(String prefix) {
        ConfigurationPropertyName parent = ConfigurationPropertyName.adapt(prefix, '.');
        Map<String, String> names = new LinkedHashMap<>();
        for (ConfigurationPropertySource source : sources) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.stream()
                        .filter(parent::isAncestorOf)
                        .forEach(n -> {
                            int index = parent.getNumberOfElements();
                            names.putIfAbsent(n.getElement(index, Form.UNIFORM), n.getElement(index, Form.ORIGINAL));
                        });
            }
        }
        return new ArrayList<>(names.values());
    }

    /** Location of the first (highest precedence) property at or below {@code prefix}. */
    public Optional<Location> locationUnder(String prefix) {
        ConfigurationPropertyName parent = ConfigurationPropertyName.adapt(prefix, '.');
        for (ConfigurationPropertySource source : sources) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                Optional<Location> location = iterable.stream()
                        .filter(n -> n.equals(parent) || parent.isAncestorOf(n))
                        .map(source::getConfigurationProperty)
                        .map(p -> toLocation(p.getOrigin()))
                        .flatMap(Optional::stream)
                        .min(java.util.Comparator.comparingInt(Location::line));
                if (location.isPresent()) {
                    return location;
                }
            }
        }
        return Optional.empty();
    }

    private ConfigurationProperty find(ConfigurationPropertyName name) {
        for (ConfigurationPropertySource source : sources) {
            ConfigurationProperty property = source.getConfigurationProperty(name);
            if (property != null) {
                return property;
            }
        }
        return null;
    }

    private <T> PropertyValue<T> unresolved(String key, String raw, Location location, Diagnostic.Code code,
            String why) {
        diagnostics.add(module, code, key + " = '" + raw + "'"
                + (location != null ? " (" + location + ")" : "") + " " + why
                + "; rules that depend on it were not evaluated");
        return new PropertyValue<>(key, PropertyValue.State.UNRESOLVED, null, raw, location);
    }

    private Optional<Location> toLocation(Origin origin) {
        for (Origin current = origin; current != null; current = current.getParent()) {
            while (current instanceof PropertySourceOrigin wrapper && wrapper.getOrigin() != null) {
                current = wrapper.getOrigin();
            }
            if (current instanceof TextResourceOrigin text && text.getResource() != null) {
                try {
                    Path file = text.getResource().getFile().toPath();
                    TextResourceOrigin.Location position = text.getLocation();
                    int line = position != null ? position.getLine() + 1 : 1;
                    int column = position != null ? position.getColumn() + 1 : 0;
                    return Optional.of(new Location(PathUtils.relative(root, file), line, column));
                } catch (IOException ex) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
