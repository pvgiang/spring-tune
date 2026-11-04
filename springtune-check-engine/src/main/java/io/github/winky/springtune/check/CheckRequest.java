package io.github.winky.springtune.check;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Input of a check run.
 *
 * @param root           analysis root; paths in the report are relative to it
 * @param modules        module directories to analyze; empty to discover them below {@code root}
 * @param profiles       Spring profiles to activate; empty to use {@code springtune.yml} profiles, else each
 *                       module's {@code spring.profiles.active}
 * @param configFile     {@code springtune.yml} to use, or {@code null} for {@code <root>/springtune.yml} if present
 * @param resolvedBuilds build facts resolved by the calling build tool, keyed by module directory; merged over
 *                       what is read from the module's build files (see {@link ResolvedBuild})
 */
public record CheckRequest(Path root, List<Path> modules, List<String> profiles, Path configFile,
        Map<Path, ResolvedBuild> resolvedBuilds) {

    public CheckRequest {
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        modules = modules == null ? List.of() : List.copyOf(modules);
        profiles = profiles == null ? List.of() : profiles.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
        Map<Path, ResolvedBuild> normalized = new LinkedHashMap<>();
        if (resolvedBuilds != null) {
            resolvedBuilds.forEach((dir, build) -> normalized.put(dir.toAbsolutePath().normalize(),
                    Objects.requireNonNull(build, "resolved build of " + dir)));
        }
        resolvedBuilds = Map.copyOf(normalized);
    }

    public CheckRequest(Path root, List<Path> modules, List<String> profiles, Path configFile) {
        this(root, modules, profiles, configFile, Map.of());
    }

    public static CheckRequest of(Path root) {
        return new CheckRequest(root, List.of(), List.of(), null);
    }

    /** Resolved build facts for {@code moduleDir}, if the caller supplied them. */
    public Optional<ResolvedBuild> resolvedBuild(Path moduleDir) {
        return Optional.ofNullable(resolvedBuilds.get(moduleDir.toAbsolutePath().normalize()));
    }
}
