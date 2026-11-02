package io.github.pvgiang.springtune.check.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.pvgiang.springtune.check.Diagnostic;
import io.github.pvgiang.springtune.check.DiagnosticSink;
import io.github.pvgiang.springtune.check.PathUtils;
import io.github.pvgiang.springtune.model.Location;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Parsed {@code src/main/java} of a module. Parsing is syntactic only (no symbol resolution). */
public final class JavaSources {

    private final List<ParsedSource> sources;

    private JavaSources(List<ParsedSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public static JavaSources empty() {
        return new JavaSources(List.of());
    }

    public static JavaSources parse(Path root, Path moduleDir, String module, DiagnosticSink diagnostics) {
        Path sourceRoot = moduleDir.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return empty();
        }
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false));
        List<ParsedSource> parsed = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException ex) {
            diagnostics.add(module, Diagnostic.Code.PARSE_ERROR, "Cannot list " + sourceRoot + ": " + ex.getMessage());
            return empty();
        }
        for (Path file : files) {
            String relative = PathUtils.relative(root, file);
            try {
                ParseResult<CompilationUnit> result = parser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    parsed.add(new ParsedSource(relative, result.getResult().get()));
                } else {
                    diagnostics.add(module, Diagnostic.Code.PARSE_ERROR, relative + " cannot be parsed ("
                            + result.getProblems().stream().findFirst().map(Object::toString).orElse("unknown")
                            + "); source rules skipped it");
                }
            } catch (IOException ex) {
                diagnostics.add(module, Diagnostic.Code.PARSE_ERROR, relative + " cannot be read: " + ex.getMessage());
            }
        }
        return new JavaSources(parsed);
    }

    public List<ParsedSource> all() {
        return sources;
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** First annotation with the given simple name (or fully-qualified name ending with it). */
    public Optional<Found<AnnotationExpr>> findAnnotation(String simpleName) {
        for (ParsedSource source : sources) {
            Optional<AnnotationExpr> match = source.unit().findFirst(AnnotationExpr.class,
                    a -> simpleName.equals(a.getName().getIdentifier()));
            if (match.isPresent()) {
                return Optional.of(new Found<>(source, match.get()));
            }
        }
        return Optional.empty();
    }

    /** Parsed file with its path relative to the analysis root. */
    public record ParsedSource(String path, CompilationUnit unit) {

        public Location locationOf(Node node) {
            return node.getBegin()
                    .map(p -> new Location(path, p.line, p.column))
                    .orElse(Location.fileLevel(path));
        }
    }

    public record Found<T extends Node>(ParsedSource source, T node) {
        public Location location() {
            return source.locationOf(node);
        }
    }
}
