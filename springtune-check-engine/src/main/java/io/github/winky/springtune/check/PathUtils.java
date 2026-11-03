package io.github.winky.springtune.check;

import java.nio.file.Path;

public final class PathUtils {

    private PathUtils() {
    }

    /** {@code file} relative to {@code root} with {@code /} separators; {@code "."} for the root itself. */
    public static String relative(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }
}
