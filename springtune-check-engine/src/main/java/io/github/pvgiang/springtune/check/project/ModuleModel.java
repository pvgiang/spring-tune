package io.github.pvgiang.springtune.check.project;

import io.github.pvgiang.springtune.check.config.PropertyValue;
import io.github.pvgiang.springtune.check.config.ResolvedConfig;
import io.github.pvgiang.springtune.check.source.JavaSources;
import io.github.pvgiang.springtune.model.Location;
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
    static final String[] SERVLET_TOMCAT_ARTIFACTS = {
        "spring-boot-starter-web", "spring-boot-starter-webmvc", "spring-boot-starter-tomcat"
    };
    static final String[] OTHER_SERVLET_CONTAINERS = {"spring-boot-starter-jetty", "spring-boot-starter-undertow"};
    static final String[] FEIGN_ARTIFACTS = {"spring-cloud-starter-openfeign"};

    private final String path;
    private final Path directory;
    private final BuildInfo build;
    private final ResolvedConfig config;
    private final JavaSources sources;

    public ModuleModel(String path, Path directory, BuildInfo build, ResolvedConfig config, JavaSources sources) {
        this.path = path;
        this.directory = directory;
        this.build = build;
        this.config = config;
        this.sources = sources;
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

    /** JDBC with HikariCP (Spring Boot's default pool). Another explicit {@code spring.datasource.type} disables it. */
    public boolean usesHikari() {
        boolean jdbc = build.hasAny(JDBC_ARTIFACTS) || config.hasAnyUnder("spring.datasource");
        if (!jdbc) {
            return false;
        }
        PropertyValue<String> type = config.getString("spring.datasource.type");
        return !type.isSet() || type.value().contains("Hikari");
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

    public boolean usesFeign() {
        return build.hasAny(FEIGN_ARTIFACTS) || sources.findAnnotation("EnableFeignClients").isPresent();
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
