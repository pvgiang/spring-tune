package io.github.pvgiang.springtune.check.project;

import io.github.pvgiang.springtune.check.DiagnosticSink;
import io.github.pvgiang.springtune.check.PathUtils;
import io.github.pvgiang.springtune.check.source.JavaSources;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-repository libraries a module depends on. Every {@code src/main/java} root under the analysis root is
 * a unit identified by its artifactId (Maven) or directory name (Gradle {@code project(':name')}); a module
 * reaches a unit through its declared dependencies, transitively. Library code matters because beans
 * defined there (an {@code AsyncConfigurer}, a {@code DataSource}) apply to the application.
 *
 * <p>Artifact ids that occur in more than one directory are ambiguous and are not followed.
 */
public final class SourceGraph {

    private record Unit(Path dir, String path, BuildInfo build) {
    }

    private final Path root;
    private final DiagnosticSink diagnostics;
    private final Map<String, Unit> byArtifactId = new HashMap<>();
    private final Map<Path, JavaSources> parsed = new HashMap<>();

    public SourceGraph(Path root, List<Path> sourceDirs, BuildInspector inspector, DiagnosticSink diagnostics) {
        this.root = root;
        this.diagnostics = diagnostics;
        Set<String> duplicates = new HashSet<>();
        for (Path dir : sourceDirs) {
            BuildInfo build = inspector.inspect(root, dir);
            String id = build.artifactId();
            if (id == null) {
                continue;
            }
            if (byArtifactId.containsKey(id)) {
                duplicates.add(id);
            }
            byArtifactId.put(id, new Unit(dir, PathUtils.relative(root, dir), build));
        }
        duplicates.forEach(byArtifactId::remove);
    }

    /** Parsed sources of every in-repo library reachable from {@code module}, excluding the module itself. */
    public List<JavaSources> librariesOf(Path moduleDir, BuildInfo moduleBuild) {
        Path self = moduleDir.toAbsolutePath().normalize();
        List<JavaSources> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(moduleBuild.artifactIds());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            Unit unit = byArtifactId.get(id);
            if (unit == null || unit.dir().toAbsolutePath().normalize().equals(self)) {
                continue;
            }
            result.add(parsed.computeIfAbsent(unit.dir(),
                    dir -> JavaSources.parse(root, dir, unit.path(), diagnostics)));
            queue.addAll(unit.build().artifactIds());
        }
        return result;
    }
}
