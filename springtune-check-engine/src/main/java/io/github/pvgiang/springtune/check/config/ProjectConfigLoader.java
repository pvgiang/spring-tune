package io.github.pvgiang.springtune.check.config;

import io.github.pvgiang.springtune.check.CheckException;
import io.github.pvgiang.springtune.check.Diagnostic;
import io.github.pvgiang.springtune.check.DiagnosticSink;
import io.github.pvgiang.springtune.check.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Builds the effective configuration of a module the way Spring Boot 2.4+ config data processing does,
 * restricted to what is visible in the repository:
 *
 * <ol>
 *   <li>Locations {@code classpath:/} then {@code classpath:/config/} (= {@code src/main/resources} and
 *       {@code src/main/resources/config}); later locations win.</li>
 *   <li>Within a location, {@code .yml}/{@code .yaml} are loaded before {@code .properties}, so properties win.</li>
 *   <li>Non-profile files first, then profile-specific files for each active profile in order (later
 *       profiles win; for one profile, {@code config/} wins over the root).</li>
 *   <li>Multi-document files: later documents win; documents with {@code spring.config.activate.on-profile}
 *       are included only when the profile expression matches. Documents with
 *       {@code spring.config.activate.on-cloud-platform} are excluded (the platform is unknown statically).</li>
 *   <li>Active profiles: the requested ones, else {@code spring.profiles.active} from the base files, else
 *       {@code spring.profiles.default} (default {@code "default"}). {@code spring.profiles.include} is
 *       appended and {@code spring.profiles.group.*} members follow their group.</li>
 * </ol>
 *
 * <p>Not modeled (documented limitations): {@code spring.config.import}, {@code spring.config.location},
 * environment variables, system properties and command-line arguments.
 */
public final class ProjectConfigLoader {

    private static final String ON_PROFILE = "spring.config.activate.on-profile";
    private static final String ON_CLOUD_PLATFORM = "spring.config.activate.on-cloud-platform";
    private static final String LEGACY_PROFILES = "spring.profiles";
    private static final Pattern FILE_NAME =
            Pattern.compile("application(?:-([^.]+))?\\.(yml|yaml|properties)");

    private final PropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
    private final PropertySourceLoader propertiesLoader = new PropertiesPropertySourceLoader();

    /**
     * @param root              analysis root (for relative paths)
     * @param moduleDir         module directory containing {@code src/main/resources}
     * @param requestedProfiles explicit profiles; empty to derive them from the configuration
     */
    public ResolvedConfig load(Path root, Path moduleDir, List<String> requestedProfiles, DiagnosticSink diagnostics) {
        String module = PathUtils.relative(root, moduleDir);
        Path resources = moduleDir.resolve("src/main/resources");
        List<Path> locations = List.of(resources, resources.resolve("config"));
        List<ConfigFile> files = discover(root, locations);

        List<Document> baseDocuments = new ArrayList<>();
        for (ConfigFile file : files) {
            if (file.profile() == null) {
                baseDocuments.addAll(read(file, module, diagnostics));
            }
        }
        List<Document> unconditionalBase = baseDocuments.stream().filter(Document::unconditional).toList();
        List<String> profiles = new ArrayList<>();
        for (String profile : activeProfiles(requestedProfiles, unconditionalBase)) {
            if (profile.contains("${")) {
                diagnostics.add(module, Diagnostic.Code.UNRESOLVED_VALUE, "Active profile '" + profile + "' is only"
                        + " known at build or run time (e.g. Maven resource filtering); it was ignored. Pass the"
                        + " profile explicitly with --profile / springtune.profiles");
            } else {
                profiles.add(profile);
            }
        }
        if (profiles.isEmpty()) {
            profiles.add("default");
        }

        List<Document> ordered = new ArrayList<>();
        Set<Path> loaded = new LinkedHashSet<>();
        for (Document document : baseDocuments) {
            if (document.activeFor(profiles)) {
                ordered.add(document);
                loaded.add(document.file().path());
            }
        }
        for (String profile : profiles) {
            for (ConfigFile file : files) {
                if (profile.equals(file.profile())) {
                    for (Document document : read(file, module, diagnostics)) {
                        if (document.activeFor(profiles)) {
                            ordered.add(document);
                            loaded.add(file.path());
                        }
                    }
                }
            }
        }

        MutablePropertySources sources = new MutablePropertySources();
        for (Document document : ordered) {
            sources.addFirst(document.source());
        }
        List<ConfigFile> withLoadedFlag = files.stream()
                .map(f -> loaded.contains(f.path()) ? f.asLoaded() : f)
                .toList();
        return new ResolvedConfig(module, root, profiles, withLoadedFlag, sources, diagnostics);
    }

    private List<ConfigFile> discover(Path root, List<Path> locations) {
        List<ConfigFile> base = new ArrayList<>();
        List<ConfigFile> profileSpecific = new ArrayList<>();
        for (Path location : locations) {
            if (!Files.isDirectory(location)) {
                continue;
            }
            List<Path> candidates;
            try (Stream<Path> listing = Files.list(location)) {
                candidates = listing.filter(Files::isRegularFile)
                        .filter(p -> FILE_NAME.matcher(p.getFileName().toString()).matches())
                        // YAML before properties: properties take precedence in the same location.
                        .sorted((a, b) -> {
                            int byExt = Integer.compare(extensionRank(a), extensionRank(b));
                            return byExt != 0 ? byExt : a.getFileName().toString().compareTo(b.getFileName().toString());
                        })
                        .toList();
            } catch (IOException ex) {
                throw new CheckException("Cannot list " + location + ": " + ex.getMessage(), ex);
            }
            for (Path candidate : candidates) {
                Matcher matcher = FILE_NAME.matcher(candidate.getFileName().toString());
                matcher.matches();
                ConfigFile file = new ConfigFile(candidate, PathUtils.relative(root, candidate), matcher.group(1), false);
                (file.profile() == null ? base : profileSpecific).add(file);
            }
        }
        List<ConfigFile> all = new ArrayList<>(base);
        all.addAll(profileSpecific);
        return all;
    }

    private static final Pattern BOOTSTRAP_NAME = Pattern.compile("bootstrap(?:-[^.]+)?\\.(yml|yaml|properties)");

    /**
     * Property names declared in {@code bootstrap*.(yml|yaml|properties)} (Spring Cloud's legacy bootstrap
     * context). The check does not resolve them; they only reveal where the real configuration comes from.
     */
    public static java.util.Set<String> bootstrapKeys(Path moduleDir) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        Path resources = moduleDir.resolve("src/main/resources");
        for (Path location : List.of(resources, resources.resolve("config"))) {
            if (!Files.isDirectory(location)) {
                continue;
            }
            try (Stream<Path> listing = Files.list(location)) {
                for (Path file : listing.filter(p -> BOOTSTRAP_NAME.matcher(p.getFileName().toString()).matches())
                        .toList()) {
                    PropertySourceLoader loader = file.toString().endsWith(".properties")
                            ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
                    FileSystemResource resource = MavenFilteredResource.hasTokens(file)
                            ? new MavenFilteredResource(file) : new FileSystemResource(file);
                    for (PropertySource<?> source : loader.load(file.getFileName().toString(), resource)) {
                        if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> e) {
                            keys.addAll(List.of(e.getPropertyNames()));
                        }
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // Unreadable bootstrap files are ignored: they only refine the "config unknown" signal.
            }
        }
        return keys;
    }

    private static int extensionRank(Path path) {
        return path.getFileName().toString().endsWith(".properties") ? 1 : 0;
    }

    private List<Document> read(ConfigFile file, String module, DiagnosticSink diagnostics) {
        PropertySourceLoader loader = file.relative().endsWith(".properties") ? propertiesLoader : yamlLoader;
        List<PropertySource<?>> sources;
        try {
            boolean filtered = MavenFilteredResource.hasTokens(file.path());
            sources = loader.load(file.relative(),
                    filtered ? new MavenFilteredResource(file.path()) : new FileSystemResource(file.path()));
        } catch (IOException | RuntimeException ex) {
            diagnostics.add(module, Diagnostic.Code.PARSE_ERROR,
                    file.relative() + " cannot be parsed: " + ex.getMessage() + "; it was ignored");
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            PropertySource<?> source = sources.get(i);
            Binder documentBinder = new Binder(ConfigurationPropertySources.from(source));
            List<String> onProfile = documentBinder.bind(ON_PROFILE, Bindable.listOf(String.class)).orElse(List.of());
            if (onProfile.isEmpty()) {
                List<String> legacy = documentBinder.bind(LEGACY_PROFILES, Bindable.listOf(String.class))
                        .orElse(List.of());
                if (!legacy.isEmpty()) {
                    diagnostics.add(module, Diagnostic.Code.UNSUPPORTED, file.relative() + " document " + (i + 1)
                            + " uses legacy 'spring.profiles'; treated as '" + ON_PROFILE
                            + "' (Spring Boot 2.4+ rejects it at startup)");
                    onProfile = legacy;
                }
            }
            boolean cloudPlatform = source.containsProperty(ON_CLOUD_PLATFORM);
            documents.add(new Document(file, source, onProfile, cloudPlatform));
        }
        return documents;
    }

    private static List<String> activeProfiles(List<String> requested, List<Document> unconditionalBase) {
        MutablePropertySources baseSources = new MutablePropertySources();
        unconditionalBase.forEach(d -> baseSources.addFirst(d.source()));
        Binder binder = new Binder(ConfigurationPropertySources.from(baseSources));

        List<String> seed = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            seed.addAll(requested);
        } else {
            seed.addAll(binder.bind("spring.profiles.active", Bindable.listOf(String.class)).orElse(List.of()));
        }
        seed.addAll(binder.bind("spring.profiles.include", Bindable.listOf(String.class)).orElse(List.of()));
        if (seed.isEmpty()) {
            seed.addAll(binder.bind("spring.profiles.default", Bindable.listOf(String.class))
                    .orElse(List.of("default")));
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String profile : seed) {
            expand(profile.trim(), binder, expanded, new LinkedHashSet<>());
        }
        return List.copyOf(expanded);
    }

    private static void expand(String profile, Binder binder, Set<String> out, Set<String> visiting) {
        if (profile.isEmpty() || !visiting.add(profile)) {
            return;
        }
        out.add(profile);
        List<String> members = binder.bind(ConfigurationPropertyName.adapt("spring.profiles.group." + profile, '.'),
                Bindable.listOf(String.class)).orElse(List.of());
        for (String member : members) {
            expand(member.trim(), binder, out, visiting);
        }
    }

    private record Document(ConfigFile file, PropertySource<?> source, List<String> onProfile, boolean cloudPlatform) {

        boolean unconditional() {
            return onProfile.isEmpty() && !cloudPlatform;
        }

        boolean activeFor(List<String> profiles) {
            if (cloudPlatform) {
                return false;
            }
            if (onProfile.isEmpty()) {
                return true;
            }
            return Profiles.of(onProfile.toArray(String[]::new)).matches(profiles::contains);
        }
    }
}
