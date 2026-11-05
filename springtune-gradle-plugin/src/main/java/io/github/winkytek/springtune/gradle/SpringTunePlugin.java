package io.github.winkytek.springtune.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Plugin {@code io.github.winkytek.springtune}. Registers {@code springtuneCheck}, which analyzes this project's
 * Spring Boot configuration, sources and deployment manifests. With the {@code java} plugin, {@code check}
 * depends on it and the resolved {@code runtimeClasspath} tells the engine which libraries are really used
 * (version catalogs, convention plugins and BOMs included).
 */
public class SpringTunePlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "springtune";
    public static final String CONFIGURATION_NAME = "springtune";
    public static final String TASK_NAME = "springtuneCheck";

    @Override
    public void apply(Project project) {
        ProviderFactory providers = project.getProviders();
        SpringTuneExtension extension = project.getExtensions().create(EXTENSION_NAME, SpringTuneExtension.class);
        extension.getFormats().convention(List.of("markdown", "sarif"));
        extension.getRootDirectory().convention(project.getRootProject().getLayout().getProjectDirectory());
        extension.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("springtune"));
        extension.getToolVersion().convention(pluginProperty("version"));

        Configuration tool = project.getConfigurations().create(CONFIGURATION_NAME, c -> {
            c.setDescription("The SpringTune check engine, run in an isolated class loader.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.defaultDependencies(deps -> deps.add(project.getDependencies().create(
                    pluginProperty("groupId") + ":springtune-check-engine:" + extension.getToolVersion().get())));
        });

        TaskProvider<SpringTuneCheck> check = project.getTasks().register(TASK_NAME, SpringTuneCheck.class, task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Checks Spring Boot configuration for pool exhaustion, hung threads and retry storms.");
            task.getModuleDirectory().set(project.getLayout().getProjectDirectory());
            task.getRootDirectory().set(extension.getRootDirectory());
            task.getProfiles().set(list(providers, "profiles").orElse(extension.getProfiles()));
            task.getFailOn().set(providers.gradleProperty("springtune.failOn").orElse(extension.getFailOn()));
            task.getFormats().set(list(providers, "formats").orElse(extension.getFormats()));
            task.getConfigFile().set(extension.getConfigFile());
            task.getOutputDirectory().set(extension.getOutputDirectory());
            task.getToolClasspath().from(tool);
        });

        project.getPluginManager().withPlugin("java", java -> {
            Configuration runtime = project.getConfigurations().getByName("runtimeClasspath");
            Provider<ResolvedComponentResult> graph = runtime.getIncoming().getResolutionResult().getRootComponent();
            check.configure(task -> {
                task.getResolvedArtifacts().set(graph.map(SpringTunePlugin::moduleNames));
                task.getSpringBootVersion().set(graph.map(root -> springBootVersion(root, "")));
                task.getUnresolvedDependencies().set(graph.map(SpringTunePlugin::unresolved));
            });
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(t -> t.dependsOn(check));
        });
    }

    private static Provider<List<String>> list(ProviderFactory providers, String name) {
        return providers.gradleProperty("springtune." + name)
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    /**
     * Module names (and project names for project dependencies) of every component in the resolved graph. A
     * dependency that failed to resolve still contributes the module name it requested: that much is a fact.
     */
    static Set<String> moduleNames(ResolvedComponentResult root) {
        Set<String> names = new TreeSet<>();
        visitUnresolved(root, selector -> {
            if (selector instanceof ModuleComponentSelector module) {
                names.add(module.getModule());
            }
        });
        walk(root, component -> {
            ComponentIdentifier id = component.getId();
            if (id instanceof ModuleComponentIdentifier module) {
                names.add(module.getModule());
            } else if (id instanceof ProjectComponentIdentifier project && component != root) {
                String path = project.getProjectPath();
                names.add(path.substring(path.lastIndexOf(':') + 1));
            }
        });
        return names;
    }

    /** The resolved {@code org.springframework.boot:spring-boot} version, or {@code ""} when absent. */
    static String springBootVersion(ResolvedComponentResult root, String absent) {
        String[] version = {absent};
        walk(root, component -> {
            if (component.getId() instanceof ModuleComponentIdentifier module
                    && module.getGroup().equals("org.springframework.boot") && module.getModule().equals("spring-boot")) {
                version[0] = module.getVersion();
            }
        });
        return version[0];
    }

    /** Display names of dependencies that could not be resolved (offline, missing repository, rate limit). */
    static List<String> unresolved(ResolvedComponentResult root) {
        Set<String> names = new TreeSet<>();
        visitUnresolved(root, selector -> names.add(selector.getDisplayName()));
        return List.copyOf(names);
    }

    private static void visitUnresolved(ResolvedComponentResult root,
            java.util.function.Consumer<ComponentSelector> visitor) {
        walk(root, component -> {
            for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof UnresolvedDependencyResult unresolved) {
                    visitor.accept(unresolved.getRequested());
                }
            }
        });
    }

    private static void walk(ResolvedComponentResult root, java.util.function.Consumer<ResolvedComponentResult> visitor) {
        Set<ComponentIdentifier> seen = new HashSet<>();
        Deque<ResolvedComponentResult> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            ResolvedComponentResult component = queue.removeFirst();
            if (!seen.add(component.getId())) {
                continue;
            }
            visitor.accept(component);
            for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof ResolvedDependencyResult resolved) {
                    queue.add(resolved.getSelected());
                }
            }
        }
    }

    static String pluginProperty(String key) {
        try (InputStream in = SpringTunePlugin.class.getResourceAsStream("/springtune-gradle-plugin.properties")) {
            Properties properties = new Properties();
            if (in == null) {
                throw new IllegalStateException("springtune-gradle-plugin.properties missing from the plugin jar");
            }
            properties.load(in);
            return properties.getProperty(key);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
