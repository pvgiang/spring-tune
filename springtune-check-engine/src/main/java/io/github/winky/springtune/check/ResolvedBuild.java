package io.github.winky.springtune.check;

import java.util.Set;

/**
 * Build facts for one module, as resolved by the build tool that runs the check (for example the Gradle
 * plugin's view of the runtime classpath). They complement what {@code BuildInspector} reads from build files
 * and fill its gaps: version catalogs, convention plugins, BOM-managed versions and transitive dependencies.
 *
 * @param artifactIds       artifact ids (Gradle: module names) on the module's runtime classpath, including
 *                          in-repo project dependencies by project name
 * @param springBootVersion resolved {@code org.springframework.boot:spring-boot} version, or {@code null}
 */
public record ResolvedBuild(Set<String> artifactIds, String springBootVersion) {

    public ResolvedBuild {
        artifactIds = artifactIds == null ? Set.of() : Set.copyOf(artifactIds);
        springBootVersion = springBootVersion == null || springBootVersion.isBlank() ? null : springBootVersion;
    }
}
