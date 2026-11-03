package io.github.pvgiang.springtune.check;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input of a check run.
 *
 * @param root       analysis root; paths in the report are relative to it
 * @param modules    module directories to analyze; empty to discover them below {@code root}
 * @param profiles   Spring profiles to activate; empty to use {@code springtune.yml} profiles, else each
 *                   module's {@code spring.profiles.active}
 * @param configFile {@code springtune.yml} to use, or {@code null} for {@code <root>/springtune.yml} if present
 */
public record CheckRequest(Path root, List<Path> modules, List<String> profiles, Path configFile) {

    public CheckRequest {
        Objects.requireNonNull(root, "root");
        root = root.toAbsolutePath().normalize();
        modules = modules == null ? List.of() : List.copyOf(modules);
        profiles = profiles == null ? List.of() : profiles.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    public static CheckRequest of(Path root) {
        return new CheckRequest(root, List.of(), List.of(), null);
    }
}
