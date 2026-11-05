package io.github.winkytek.springtune.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

/**
 * Runs the SpringTune check on one project. The engine runs in a worker with an isolated class loader built
 * from {@link #getToolClasspath()}, so its Spring Boot and Jackson versions never meet the build's own.
 *
 * <p>The task is never up-to-date: the engine reads files anywhere below {@link #getRootDirectory()} (parent
 * build files, in-repo libraries, Helm values), which cannot be declared as precise inputs.
 */
public abstract class SpringTuneCheck extends DefaultTask {

    public SpringTuneCheck() {
        doNotTrackState("SpringTune reads build files, library sources and deployment manifests anywhere below"
                + " the root directory, so its inputs cannot be declared precisely");
    }

    /** The project to analyze. */
    @Internal
    public abstract DirectoryProperty getModuleDirectory();

    /** Root for relative report paths and the default {@code springtune.yml}. */
    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @Input
    public abstract ListProperty<String> getProfiles();

    @Input
    @Optional
    public abstract Property<String> getFailOn();

    @Input
    public abstract ListProperty<String> getFormats();

    @Internal
    public abstract RegularFileProperty getConfigFile();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /** {@code springtune-check-engine} and its dependencies. */
    @Classpath
    public abstract ConfigurableFileCollection getToolClasspath();

    /** Module names on the resolved runtime classpath; empty without the {@code java} plugin. */
    @Input
    public abstract SetProperty<String> getResolvedArtifacts();

    /** Runtime dependencies that failed to resolve; reported as a warning because detection is then partial. */
    @Input
    public abstract ListProperty<String> getUnresolvedDependencies();

    /** Resolved Spring Boot version, or empty. */
    @Input
    @Optional
    public abstract Property<String> getSpringBootVersion();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    public void check() {
        WorkQueue queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getToolClasspath()));
        queue.submit(CheckWorkAction.class, parameters -> {
            parameters.getModuleDirectory().set(getModuleDirectory());
            parameters.getRootDirectory().set(getRootDirectory());
            parameters.getProfiles().set(getProfiles());
            parameters.getFailOn().set(getFailOn());
            parameters.getFormats().set(getFormats());
            parameters.getConfigFile().set(getConfigFile());
            parameters.getOutputDirectory().set(getOutputDirectory());
            parameters.getResolvedArtifacts().set(getResolvedArtifacts());
            parameters.getSpringBootVersion().set(getSpringBootVersion());
            parameters.getUnresolvedDependencies().set(getUnresolvedDependencies());
            parameters.getProjectPath().set(getPath());
        });
        queue.await();
    }
}
