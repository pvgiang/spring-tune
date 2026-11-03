package io.github.winky.springtune.check.project;

import io.github.winky.springtune.check.config.PropertyValue;
import io.github.winky.springtune.check.config.ResolvedConfig;
import io.github.winky.springtune.check.source.JavaSources;
import io.github.winky.springtune.model.Location;
import java.nio.file.Path;

/**
 * Everything the rules know about one Spring Boot module.
 *
 * <p>Technology detection decides which rules apply. It deliberately prefers silence: a rule family runs
 * only when the build declares the library or the configuration already uses its properties.
 */
public final class ModuleModel {

    static final String[] JDBC_ARTIFACTS = {
        "spring-boot-starter-jdbc", "spring-boot-starter-data-jpa", "spring-boot-starter-data-jdbc",
        "spring-boot-starter-jooq", "spring-boot-jdbc", "HikariCP"
    };
    /** Libraries that register their own pooled DataSource instead of Boot's HikariCP. */
    static final String[] OTHER_POOL_ARTIFACTS = {
        "druid-spring-boot-starter", "druid-spring-boot-3-starter", "druid-spring-boot-4-starter",
        "dynamic-datasource-spring-boot-starter", "dynamic-datasource-spring-boot3-starter"
    };
    static final String[] SERVLET_TOMCAT_ARTIFACTS = {
        "spring-boot-starter-web", "spring-boot-starter-webmvc", "spring-boot-starter-tomcat"
    };
    static final String[] OTHER_SERVLET_CONTAINERS = {"spring-boot-starter-jetty", "spring-boot-starter-undertow"};

    private final String path;
    private final Path directory;
    private final BuildInfo build;
    private final ResolvedConfig config;
    private final JavaSources sources;
    private Boolean definesDataSource;
    private java.util.Optional<String> configUnknownReason;

    private final java.util.List<JavaSources> libraries;

    public ModuleModel(String path, Path directory, BuildInfo build, ResolvedConfig config, JavaSources sources) {
        this(path, directory, build, config, sources, java.util.List.of());
    }

    /**
     * @param libraries parsed sources of in-repo libraries the module depends on (see {@link SourceGraph})
     */
    public ModuleModel(String path, Path directory, BuildInfo build, ResolvedConfig config, JavaSources sources,
            java.util.List<JavaSources> libraries) {
        this.path = path;
        this.directory = directory;
        this.build = build;
        this.config = config;
        this.sources = sources;
        this.libraries = java.util.List.copyOf(libraries);
    }

    /** The module's own sources followed by those of its in-repo library dependencies. */
    public java.util.List<JavaSources.ParsedSource> reachableSources() {
        java.util.List<JavaSources.ParsedSource> all = new java.util.ArrayList<>(sources.all());
        libraries.forEach(library -> all.addAll(library.all()));
        return all;
    }

    public String path() {
        return path;
    }

    public Path directory() {
        return directory;
    }

    public BuildInfo build() {
        return build;
    }

    public ResolvedConfig config() {
        return config;
    }

    public JavaSources sources() {
        return sources;
    }

    /** The module talks to a database over JDBC (any pool). */
    public boolean usesJdbc() {
        return build.hasAny(JDBC_ARTIFACTS) || build.hasAny(OTHER_POOL_ARTIFACTS)
                || config.hasAnyUnder("spring.datasource");
    }

    /**
     * JDBC through Spring Boot's auto-configured HikariCP pool. False when another pool or a multi-datasource
     * library owns the DataSource: an explicit non-Hikari {@code spring.datasource.type}, the Druid starter or
     * {@code spring.datasource.druid.*}, or {@code spring.datasource.dynamic.*} (dynamic-datasource), whose
     * per-datasource pools the check does not model.
     */
    public boolean usesHikari() {
        if (!usesJdbc()) {
            return false;
        }
        PropertyValue<String> type = config.getString("spring.datasource.type");
        if (type.isSet()) {
            return type.value().contains("Hikari");
        }
        if (isSpringBoot1() && !build.hasAny("HikariCP")) {
            return false; // Spring Boot 1.x auto-configures tomcat-jdbc first
        }
        return !build.hasAny(OTHER_POOL_ARTIFACTS)
                && !config.hasAnyUnder("spring.datasource.druid")
                && !config.hasAnyUnder("spring.datasource.dynamic");
    }

    /**
     * HikariCP configured through {@code spring.datasource.hikari.*}: {@link #usesHikari()}, and the module
     * neither builds its own DataSource in code (then those properties may not apply) nor relocates its
     * configuration files ({@link #configFilesKnown()}).
     */
    public boolean usesBootManagedHikari() {
        return usesHikari() && !definesDataSourceInCode() && !excludesDataSourceAutoConfiguration()
                && configFilesKnown();
    }

    /** {@code DataSourceAutoConfiguration} excluded via annotation attribute or {@code spring.autoconfigure.exclude}. */
    public boolean excludesDataSourceAutoConfiguration() {
        PropertyValue<String> exclude = config.getString("spring.autoconfigure.exclude");
        if (exclude.isSet() && exclude.value().contains("DataSourceAutoConfiguration")) {
            return true;
        }
        return sources.all().stream().anyMatch(source -> source.unit().findFirst(
                com.github.javaparser.ast.expr.NormalAnnotationExpr.class,
                a -> java.util.Set.of("SpringBootApplication", "EnableAutoConfiguration")
                        .contains(a.getName().getIdentifier())
                        && a.getPairs().stream().anyMatch(p -> p.getNameAsString().startsWith("exclude")
                                && p.getValue().toString().contains("DataSourceAutoConfiguration"))).isPresent());
    }

    public boolean isSpringBoot1() {
        String version = build.springBootVersion();
        return version != null && version.startsWith("1.");
    }

    /** A {@code @Bean} returning a DataSource, or a pool/builder constructed in the module's own code. */
    public boolean definesDataSourceInCode() {
        if (definesDataSource == null) {
            boolean libraryBean = libraries.stream().flatMap(l -> l.all().stream()).anyMatch(source ->
                    source.unit().findFirst(com.github.javaparser.ast.body.MethodDeclaration.class,
                            m -> m.getAnnotationByName("Bean").isPresent()
                                    && m.getType().asString().endsWith("DataSource")).isPresent());
            definesDataSource = libraryBean || sources.all().stream().anyMatch(source ->
                    source.unit().findFirst(com.github.javaparser.ast.body.MethodDeclaration.class,
                            m -> m.getAnnotationByName("Bean").isPresent()
                                    && m.getType().asString().endsWith("DataSource")).isPresent()
                            || source.unit().findFirst(com.github.javaparser.ast.expr.ObjectCreationExpr.class,
                                    c -> java.util.Set.of("HikariDataSource", "HikariConfig")
                                            .contains(c.getType().getNameAsString())).isPresent()
                            || source.unit().findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class,
                                    c -> c.getNameAsString().equals("create") && c.getScope()
                                            .filter(sc -> sc.toString().endsWith("DataSourceBuilder")).isPresent())
                                    .isPresent());
        }
        return definesDataSource;
    }

    /** {@code true} unless {@link #configUnknownReason()} names a reason. */
    public boolean configFilesKnown() {
        return configUnknownReason().isEmpty();
    }

    private static final java.util.List<String> REMOTE_IMPORT_PREFIXES = java.util.List.of(
            "configserver:", "nacos:", "consul:", "zookeeper:", "vault:", "aws-parameterstore:",
            "aws-secretsmanager:", "aws-s3:", "kubernetes:", "apollo:");
    private static final java.util.Map<String, String> REMOTE_CONFIG_PREFIXES = java.util.Map.of(
            "spring.cloud.config.uri", "Spring Cloud Config Server",
            "spring.cloud.config.discovery", "Spring Cloud Config Server",
            "spring.cloud.nacos.config", "Nacos",
            "spring.cloud.consul.config", "Consul",
            "spring.cloud.zookeeper.config", "ZooKeeper",
            "spring.cloud.vault", "Vault",
            "apollo.", "Apollo");

    /**
     * Why the module's local config files are not its whole configuration, or empty when they are. When
     * non-empty, a property missing from the files proves nothing, so rules that report missing properties stay
     * silent: the code sets {@code spring.config.name}/{@code spring.config.location}; {@code spring.config.import}
     * loads a remote source; or (bootstrap) config points at a config server such as Spring Cloud Config or Nacos.
     */
    public java.util.Optional<String> configUnknownReason() {
        if (configUnknownReason == null) {
            configUnknownReason = java.util.Optional.ofNullable(computeConfigUnknownReason());
        }
        return configUnknownReason;
    }

    private String computeConfigUnknownReason() {
        boolean relocated = sources.all().stream().anyMatch(source ->
                source.unit().findFirst(com.github.javaparser.ast.expr.StringLiteralExpr.class,
                        l -> l.getValue().contains("spring.config.name")
                                || l.getValue().contains("spring.config.location")).isPresent());
        if (relocated) {
            return "the code sets spring.config.name or spring.config.location, so the real config files are unknown";
        }
        PropertyValue<String> imports = config.getString("spring.config.import");
        if (imports.isSet()) {
            for (String entry : imports.value().split(",")) {
                String value = entry.trim().replaceFirst("^optional:", "");
                if (REMOTE_IMPORT_PREFIXES.stream().anyMatch(value::startsWith)) {
                    return "spring.config.import loads remote configuration (" + entry.trim() + ")";
                }
            }
        }
        java.util.Set<String> keys = new java.util.TreeSet<>(
                io.github.winky.springtune.check.config.ProjectConfigLoader.bootstrapKeys(directory));
        for (var prefix : REMOTE_CONFIG_PREFIXES.entrySet()) {
            boolean inBootstrap = keys.stream().anyMatch(k -> k.startsWith(prefix.getKey()));
            if (inBootstrap || config.hasAnyUnder(prefix.getKey().replaceAll("\\.$", ""))) {
                return "configuration is loaded from " + prefix.getValue()
                        + (inBootstrap ? " (bootstrap config)" : "") + ", which the check cannot see";
            }
        }
        return null;
    }

    /** Servlet stack on embedded Tomcat (Spring Boot's default servlet container). */
    public boolean usesTomcat() {
        boolean declared = build.hasAny(SERVLET_TOMCAT_ARTIFACTS) && !build.hasAny(OTHER_SERVLET_CONTAINERS);
        return declared || config.hasAnyUnder("server.tomcat");
    }

    public boolean usesVirtualThreads() {
        PropertyValue<Boolean> enabled = config.getBoolean("spring.threads.virtual.enabled");
        return enabled.isSet() && enabled.value();
    }

    /**
     * Feign calls go through Netflix Ribbon (Spring Cloud Hoxton and earlier, i.e. Spring Boot before 2.4):
     * the Ribbon artifact, Ribbon config, or a discovery client on such an old Boot. Ribbon then governs
     * timeouts (Spring Cloud's Ribbon default is 1 s), not Feign's 60 s default.
     */
    public boolean usesRibbon() {
        if (build.hasAny("spring-cloud-starter-netflix-ribbon") || config.hasAnyUnder("ribbon")) {
            return true;
        }
        return bootBefore(2, 4) && build.hasAny("spring-cloud-starter-netflix-eureka-client",
                "spring-cloud-starter-consul-discovery", "spring-cloud-starter-zookeeper-discovery");
    }

    /** {@code true} if the literal Spring Boot version is known and older than {@code major.minor}. */
    public boolean bootBefore(int major, int minor) {
        String version = build.springBootVersion();
        if (version == null) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version);
        if (!m.find()) {
            return false;
        }
        int actualMajor = Integer.parseInt(m.group(1));
        int actualMinor = Integer.parseInt(m.group(2));
        return actualMajor < major || (actualMajor == major && actualMinor < minor);
    }

    /**
     * Spring Cloud OpenFeign clients exist: {@code @EnableFeignClients} in the module or a library it depends on.
     * The artifact alone is not enough (no clients are registered without the annotation; other SDKs such as
     * ShenYu ship their own Feign-like annotations).
     */
    public boolean usesFeign() {
        return reachableSources().stream().anyMatch(source -> source.unit().findFirst(
                com.github.javaparser.ast.expr.AnnotationExpr.class,
                a -> a.getName().getIdentifier().equals("EnableFeignClients")).isPresent());
    }

    /** Where a finding about a missing setting should point: most specific config file, else the build file. */
    public Location fallbackLocation() {
        return config.primaryLocation()
                .or(() -> java.util.Optional.ofNullable(build.buildFile()).map(Location::fileLevel))
                .or(() -> sources.findAnnotation("SpringBootApplication").map(JavaSources.Found::location))
                .orElseGet(() -> Location.fileLevel(path));
    }

    /** Location of {@code value} if it is set in a file, else {@link #fallbackLocation()}. */
    public Location locationOf(PropertyValue<?> value) {
        return value.location() != null ? value.location() : fallbackLocation();
    }
}
