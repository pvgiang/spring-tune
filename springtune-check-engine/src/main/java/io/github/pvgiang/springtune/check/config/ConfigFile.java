package io.github.pvgiang.springtune.check.config;

import java.nio.file.Path;

/**
 * A discovered {@code application[-profile].(yml|yaml|properties)} file.
 *
 * @param path     absolute path
 * @param relative path relative to the analysis root, {@code /}-separated
 * @param profile  profile suffix, or {@code null} for the base file
 * @param loaded   whether it contributes to the effective configuration for the active profiles
 */
public record ConfigFile(Path path, String relative, String profile, boolean loaded) {

    ConfigFile asLoaded() {
        return new ConfigFile(path, relative, profile, true);
    }
}
