package io.github.winky.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@code *Writer} classes may call a pool or executor size setter. Scans the module's main sources, so a
 * new code path that writes a knob without going through the guard fails the build.
 */
class WriterIsolationTest {

    private static final Pattern SETTER = Pattern.compile(
            "\\.\\s*(setMaximumPoolSize|setCorePoolSize|setMaxPoolSize|setMinimumIdle)\\s*\\(");

    @Test
    void sizeSettersAreCalledOnlyFromWriters() throws IOException {
        Path sources = Path.of("src/main/java");
        assertThat(sources).isDirectory();
        List<String> violations = new ArrayList<>();
        List<String> writersWithSetters = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = SETTER.matcher(lines.get(i));
                    if (matcher.find()) {
                        if (name.endsWith("Writer.java")) {
                            writersWithSetters.add(name);
                        } else {
                            violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        assertThat(violations).as("size setters outside *Writer classes").isEmpty();
        assertThat(writersWithSetters).as("the scan finds the writers' own setter calls")
                .contains("HikariPoolWriter.java", "ExecutorPoolWriter.java");
    }
}
