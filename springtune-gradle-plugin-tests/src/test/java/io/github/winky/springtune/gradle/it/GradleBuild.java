package io.github.winky.springtune.gradle.it;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Copies a fixture build into a temporary directory and runs a real Gradle installation on it. The plugin is
 * resolved by id from the Maven repository staged by {@code invoker:install}, like a user's build would.
 */
final class GradleBuild {

    record Result(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }

    private final Path projectDir;
    private final Path gradle;
    private String warningMode = "fail";

    private GradleBuild(Path projectDir, Path gradle) {
        this.projectDir = projectDir;
        this.gradle = gradle;
    }

    static GradleBuild of(String fixture, Path target) {
        Path gradle = locateGradle();
        Path source = Path.of(System.getProperty("springtune.it.fixtures")).resolve(fixture);
        String repoUrl = Path.of(System.getProperty("springtune.it.repo")).toUri().toString();
        String version = System.getProperty("springtune.it.version");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                String text = Files.readString(file);
                Files.writeString(copy, text.replace("@REPO_URL@", repoUrl).replace("@VERSION@", version));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new GradleBuild(target, gradle);
    }

    /**
     * Any Gradle deprecation fails the build by default, so Gradle 10/11 breakage in SpringTune shows up early.
     * Fixtures that apply third-party plugins with their own deprecations relax this to {@code all}.
     */
    GradleBuild warningMode(String mode) {
        this.warningMode = mode;
        return this;
    }

    Path dir() {
        return projectDir;
    }

    Result run(String... args) {
        List<String> command = new ArrayList<>();
        command.add(gradle.toString());
        command.addAll(List.of("--no-daemon", "--console=plain", "--stacktrace", "--max-workers=2",
                "--warning-mode=" + warningMode,
                // Back off and retry when a repository rate-limits (HTTP 429) instead of failing the test.
                "-Dorg.gradle.internal.repository.max.retries=8",
                "-Dorg.gradle.internal.repository.initial.backoff=2000"));
        command.addAll(List.of(args));
        try {
            Path log = Files.createTempFile("gradle-it", ".log");
            Process process = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("Gradle timed out: " + command);
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            Files.deleteIfExists(log);
            System.out.println("---- gradle " + String.join(" ", args) + " (exit " + process.exitValue() + ")\n"
                    + output);
            return new Result(process.exitValue(), output);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    /** {@code -Dspringtune.it.gradle} (binary or installation dir), else {@code gradle} on {@code PATH}, else {@code GRADLE_HOME}. */
    private static Path locateGradle() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("springtune.it.gradle", "");
        if (!configured.isBlank()) {
            Path path = Path.of(configured);
            candidates.add(Files.isDirectory(path) ? path.resolve("bin").resolve(executable()) : path);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                candidates.add(Path.of(dir, executable()));
            }
        }
        String home = System.getenv("GRADLE_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "bin", executable()));
        }
        Path found = candidates.stream().filter(Files::isExecutable).findFirst().orElse(null);
        boolean required = Boolean.getBoolean("springtune.it.requireGradle");
        if (found == null && required) {
            throw new AssertionError("No Gradle installation found (springtune.it.requireGradle=true)");
        }
        assumeTrue(found != null, "No Gradle installation found: set -Dspringtune.it.gradle, GRADLE_HOME or PATH");
        return found;
    }

    private static String executable() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "gradle.bat" : "gradle";
    }
}
