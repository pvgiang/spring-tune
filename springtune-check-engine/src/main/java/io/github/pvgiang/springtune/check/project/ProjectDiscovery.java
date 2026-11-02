package io.github.pvgiang.springtune.check.project;

import io.github.pvgiang.springtune.check.CheckException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds Spring Boot modules below a root. A directory is a module when it has
 * {@code src/main/resources[/config]/application*.(yml|yaml|properties)} or a class annotated with
 * {@code @SpringBootApplication} under {@code src/main/java}. Test sources are never scanned.
 */
public final class ProjectDiscovery {

    private static final Set<String> SKIPPED = Set.of("target", "build", "out", "node_modules", "bin", "src");
    private static final Pattern CONFIG = Pattern.compile("application(-[^.]+)?\\.(yml|yaml|properties)");
    private static final int MAX_DEPTH = 8;

    public List<Path> discover(Path root) {
        if (!Files.isDirectory(root)) {
            throw new CheckException("Not a directory: " + root);
        }
        List<Path> modules = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && (name.startsWith(".") || SKIPPED.contains(name))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (isModule(dir)) {
                        modules.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new CheckException("Cannot scan " + root + ": " + ex.getMessage(), ex);
        }
        modules.sort(null);
        return modules;
    }

    /** {@code true} if {@code dir} looks like a Spring Boot module (see class docs). */
    public static boolean isModule(Path dir) {
        Path resources = dir.resolve("src/main/resources");
        return hasConfig(resources) || hasConfig(resources.resolve("config"))
                || hasBootApplication(dir.resolve("src/main/java"));
    }

    private static boolean hasConfig(Path location) {
        if (!Files.isDirectory(location)) {
            return false;
        }
        try (Stream<Path> files = Files.list(location)) {
            return files.anyMatch(p -> CONFIG.matcher(p.getFileName().toString()).matches());
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean hasBootApplication(Path sourceRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(p -> p.toString().endsWith(".java")).anyMatch(p -> {
                try {
                    return Files.readString(p).contains("@SpringBootApplication");
                } catch (IOException ex) {
                    return false;
                }
            });
        } catch (IOException ex) {
            return false;
        }
    }
}
