package io.github.winkytek.springtune.check.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds throw-away project trees in a temp directory for tests. */
public final class TestProject {

    private final Path root;

    private TestProject(Path root) {
        this.root = root;
    }

    public static TestProject in(Path root) {
        return new TestProject(root);
    }

    public Path root() {
        return root;
    }

    public TestProject file(String relative, String content) {
        try {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content.stripIndent());
            return this;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A minimal Maven Spring Boot module with the given starters. */
    public TestProject pom(String modulePath, String... artifactIds) {
        StringBuilder deps = new StringBuilder();
        for (String artifactId : artifactIds) {
            deps.append("<dependency><groupId>x</groupId><artifactId>").append(artifactId)
                    .append("</artifactId></dependency>");
        }
        String prefix = modulePath.equals(".") ? "" : modulePath + "/";
        return file(prefix + "pom.xml", """
                <project><parent><groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId><version>3.5.16</version></parent>
                <dependencies>%s</dependencies></project>
                """.formatted(deps));
    }

    public TestProject resource(String modulePath, String name, String content) {
        String prefix = modulePath.equals(".") ? "" : modulePath + "/";
        return file(prefix + "src/main/resources/" + name, content);
    }

    public TestProject java(String modulePath, String className, String content) {
        String prefix = modulePath.equals(".") ? "" : modulePath + "/";
        return file(prefix + "src/main/java/demo/" + className + ".java", content);
    }
}
