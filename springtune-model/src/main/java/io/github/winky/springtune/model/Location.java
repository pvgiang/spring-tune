package io.github.winky.springtune.model;

import java.util.Objects;

/**
 * Where a finding points to.
 *
 * @param path   path relative to the analysis root, always with {@code /} separators
 * @param line   1-based line number (1 when the finding concerns the file as a whole)
 * @param column 1-based column number, or 0 when unknown
 */
public record Location(String path, int line, int column) {

    public Location {
        Objects.requireNonNull(path, "path");
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1 but was " + line);
        }
        if (column < 0) {
            throw new IllegalArgumentException("column must be >= 0 but was " + column);
        }
    }

    public static Location fileLevel(String path) {
        return new Location(path, 1, 0);
    }

    @Override
    public String toString() {
        return column > 0 ? path + ":" + line + ":" + column : path + ":" + line;
    }
}
