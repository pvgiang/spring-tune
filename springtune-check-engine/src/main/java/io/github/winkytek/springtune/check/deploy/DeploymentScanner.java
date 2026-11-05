package io.github.winkytek.springtune.check.deploy;

import io.github.winkytek.springtune.check.CheckException;
import io.github.winkytek.springtune.check.Diagnostic;
import io.github.winkytek.springtune.check.DiagnosticSink;
import io.github.winkytek.springtune.check.PathUtils;
import io.github.winkytek.springtune.check.settings.SpringTuneSettings;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Determines the maximum replica count of a service. Precedence:
 *
 * <ol>
 *   <li>{@code services[].maxReplicas} in {@code springtune.yml};</li>
 *   <li>files listed in {@code services[].deployment};</li>
 *   <li>auto-discovery inside the module directory, used only when exactly one file yields a value.</li>
 * </ol>
 *
 * <p>Supported files: Kubernetes manifests ({@code HorizontalPodAutoscaler.spec.maxReplicas} wins over
 * {@code Deployment/StatefulSet.spec.replicas}) and Helm values ({@code autoscaling.maxReplicas} when
 * {@code autoscaling.enabled: true}, else {@code replicaCount}). Helm templates are not rendered.
 */
public final class DeploymentScanner {

    private static final Set<String> MANIFEST_DIRS =
            Set.of("k8s", "kubernetes", "deploy", "deployment", "manifests", "helm", "charts", "chart");
    private static final Set<String> SKIPPED = Set.of("src", "target", "build", "node_modules", "templates");

    public Optional<ReplicaInfo> resolve(Path root, Path moduleDir, String module,
            SpringTuneSettings.Service service, DiagnosticSink diagnostics) {
        if (service != null && service.maxReplicas() != null) {
            return Optional.of(new ReplicaInfo(service.maxReplicas(), "springtune.yml services[path=" + module + "]"));
        }
        if (service != null && !service.deployment().isEmpty()) {
            List<Candidate> candidates = new ArrayList<>();
            for (String relative : service.deployment()) {
                Path file = root.resolve(relative);
                if (!Files.isRegularFile(file)) {
                    throw new CheckException("services[path=" + module + "].deployment file not found: " + relative);
                }
                Optional<Candidate> candidate = read(root, file, module, diagnostics, true);
                candidate.ifPresent(candidates::add);
            }
            return combine(candidates);
        }
        List<Candidate> discovered = new ArrayList<>();
        for (Path file : discover(moduleDir)) {
            read(root, file, module, diagnostics, false).ifPresent(discovered::add);
        }
        if (discovered.size() > 1) {
            diagnostics.add(module, Diagnostic.Code.RULE_SKIPPED, "Several deployment files declare replicas ("
                    + String.join(", ", discovered.stream().map(Candidate::file).toList())
                    + "); list the right one under services[].deployment in springtune.yml");
            return Optional.empty();
        }
        return discovered.stream().findFirst()
                .map(c -> new ReplicaInfo(c.replicas(), c.file() + " " + c.key() + " (auto-discovered)"));
    }

    private static Optional<ReplicaInfo> combine(List<Candidate> candidates) {
        List<Candidate> hpas = candidates.stream().filter(Candidate::autoscaler).toList();
        List<Candidate> pool = hpas.isEmpty() ? candidates : hpas;
        return pool.stream()
                .max(java.util.Comparator.comparingInt(Candidate::replicas))
                .map(c -> new ReplicaInfo(c.replicas(), c.file() + " " + c.key()));
    }

    private List<Path> discover(Path moduleDir) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(moduleDir, Set.of(), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(moduleDir) && (name.startsWith(".") || SKIPPED.contains(name))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    boolean yaml = name.endsWith(".yaml") || name.endsWith(".yml");
                    boolean values = name.equals("values.yaml") || name.equals("values.yml");
                    boolean inManifestDir = false;
                    for (Path p = file.getParent(); p != null && !p.equals(moduleDir.getParent()); p = p.getParent()) {
                        if (p.getFileName() != null && MANIFEST_DIRS.contains(p.getFileName().toString())) {
                            inManifestDir = true;
                            break;
                        }
                    }
                    if (yaml && (values || inManifestDir)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            return List.of();
        }
        files.sort(null);
        return files;
    }

    private record Candidate(String file, String key, int replicas, boolean autoscaler) {
    }

    @SuppressWarnings("unchecked")
    private static Optional<Candidate> read(Path root, Path file, String module, DiagnosticSink diagnostics,
            boolean explicit) {
        String relative = PathUtils.relative(root, file);
        List<Object> documents = new ArrayList<>();
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object document : yaml.loadAll(Files.readString(file))) {
                documents.add(document);
            }
        } catch (IOException | RuntimeException ex) {
            if (explicit) {
                diagnostics.add(module, Diagnostic.Code.PARSE_ERROR, relative + " is not plain YAML (Helm templates"
                        + " are not rendered): " + ex.getMessage().lines().findFirst().orElse(""));
            }
            return Optional.empty();
        }
        Candidate best = null;
        Integer workloadReplicas = null;
        for (Object document : documents) {
            if (!(document instanceof Map<?, ?> map)) {
                continue;
            }
            Object kind = map.get("kind");
            Map<String, Object> spec = map.get("spec") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
            if ("HorizontalPodAutoscaler".equals(kind) && spec.get("maxReplicas") instanceof Integer max) {
                if (best == null || max > best.replicas()) {
                    best = new Candidate(relative, "HorizontalPodAutoscaler.spec.maxReplicas", max, true);
                }
            } else if (("Deployment".equals(kind) || "StatefulSet".equals(kind))
                    && spec.get("replicas") instanceof Integer replicas) {
                workloadReplicas = workloadReplicas == null ? replicas : Math.max(workloadReplicas, replicas);
            } else if (kind == null) {
                Object autoscaling = map.get("autoscaling");
                if (autoscaling instanceof Map<?, ?> a && Boolean.TRUE.equals(a.get("enabled"))
                        && a.get("maxReplicas") instanceof Integer max) {
                    best = new Candidate(relative, "autoscaling.maxReplicas", max, true);
                } else if (map.get("replicaCount") instanceof Integer count && best == null) {
                    return Optional.of(new Candidate(relative, "replicaCount", count, false));
                }
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        if (workloadReplicas != null) {
            return Optional.of(new Candidate(relative, "spec.replicas", workloadReplicas, false));
        }
        return Optional.empty();
    }
}
