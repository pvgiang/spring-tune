package io.github.winky.springtune.check.project;

import io.github.winky.springtune.check.ResolvedBuild;
import java.util.LinkedHashSet;
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

    /**
     * These facts with {@code resolved} merged in: artifact ids are the union (declared and resolved), and the
     * resolved Spring Boot version wins because it is what actually runs.
     */
    public BuildInfo merge(ResolvedBuild resolved) {
        Set<String> union = new LinkedHashSet<>(artifactIds);
        union.addAll(resolved.artifactIds());
        String version = resolved.springBootVersion() != null ? resolved.springBootVersion() : springBootVersion;
        return new BuildInfo(buildFile, artifactId, version, union);
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
