package io.github.pvgiang.springtune.check.project;

import java.util.Set;

/**
 * Facts read from the module's build file(s).
 *
 * @param buildFile         relative path of the module build file, or {@code null} when none exists
 * @param artifactId        the module's own Maven artifactId (Gradle: directory name), or {@code null}
 * @param springBootVersion Spring Boot version when it can be read literally, else {@code null}
 * @param artifactIds       dependency artifact ids declared in the module and its ancestor build files
 */
public record BuildInfo(String buildFile, String artifactId, String springBootVersion, Set<String> artifactIds) {

    public BuildInfo {
        artifactIds = Set.copyOf(artifactIds);
    }

    public boolean known() {
        return buildFile != null;
    }

    public boolean hasAny(String... candidates) {
        for (String candidate : candidates) {
            if (artifactIds.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
