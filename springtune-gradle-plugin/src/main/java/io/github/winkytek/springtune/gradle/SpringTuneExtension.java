package io.github.winkytek.springtune.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code springtune { }} block. Every property can also be set on the command line with
 * {@code -Pspringtune.<name>=...} (lists comma-separated), which wins over the build script.
 */
public abstract class SpringTuneExtension {

    /** Spring profiles to activate. Default: {@code springtune.yml} {@code profiles}, else {@code spring.profiles.active}. */
    public abstract ListProperty<String> getProfiles();

    /** {@code ERROR}, {@code WARNING}, {@code INFO} or {@code NONE}. Default: {@code springtune.yml} {@code failOn}, else {@code ERROR}. */
    public abstract Property<String> getFailOn();

    /** Report files to write: {@code text}, {@code markdown}, {@code json}, {@code sarif}. Default: markdown and sarif. */
    public abstract ListProperty<String> getFormats();

    /** Explicit {@code springtune.yml}. Default: {@code <rootDirectory>/springtune.yml} if it exists. */
    public abstract RegularFileProperty getConfigFile();

    /** Root for relative report paths and the default {@code springtune.yml}. Default: the root project directory. */
    public abstract DirectoryProperty getRootDirectory();

    /** Where reports are written. Default: {@code build/springtune}. */
    public abstract DirectoryProperty getOutputDirectory();

    /** Version of {@code springtune-check-engine} to run. Default: the plugin's own version. */
    public abstract Property<String> getToolVersion();
}
