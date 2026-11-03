package io.github.winky.springtune.check.settings;

import io.github.winky.springtune.check.CheckException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/** Reads {@code springtune.yml} with Spring Boot's YAML loader and {@link Binder}. */
public final class SettingsLoader {

    public static final String DEFAULT_FILE_NAME = "springtune.yml";

    private SettingsLoader() {
    }

    /**
     * @param file          explicit file, or {@code null} to look for {@value #DEFAULT_FILE_NAME} in {@code root}
     * @param root          analysis root
     * @param knownRuleIds  for validation of rule references
     */
    public static SpringTuneSettings load(Path file, Path root, Set<String> knownRuleIds) {
        Path effective = file != null ? file : root.resolve(DEFAULT_FILE_NAME);
        if (!Files.isRegularFile(effective)) {
            if (file != null) {
                throw new CheckException("Configuration file not found: " + file);
            }
            return SpringTuneSettings.empty();
        }
        SpringTuneSettings settings = bind(effective);
        List<String> errors = settings.validate(knownRuleIds);
        if (!errors.isEmpty()) {
            throw new CheckException("Invalid " + effective + ":\n  - " + String.join("\n  - ", errors));
        }
        return settings;
    }

    private static SpringTuneSettings bind(Path file) {
        try {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load(file.getFileName().toString(), new FileSystemResource(file));
            MutablePropertySources sources = new MutablePropertySources();
            documents.forEach(sources::addLast);
            Binder binder = new Binder(ConfigurationPropertySources.from(sources));
            return binder.bind(ConfigurationPropertyName.EMPTY, Bindable.of(SpringTuneSettings.class))
                    .orElseGet(SpringTuneSettings::empty);
        } catch (IOException | RuntimeException ex) {
            Throwable cause = ex instanceof BindException && ex.getCause() != null ? ex.getCause() : ex;
            throw new CheckException("Cannot read " + file + ": " + ex.getMessage()
                    + (cause != ex ? " (" + cause.getMessage() + ")" : ""), ex);
        }
    }
}
