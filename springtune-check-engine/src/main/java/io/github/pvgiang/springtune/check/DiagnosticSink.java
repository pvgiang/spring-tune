package io.github.pvgiang.springtune.check;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Collects diagnostics, ignoring exact duplicates. Not thread-safe; one sink per check run. */
public final class DiagnosticSink {

    private final Set<Diagnostic> diagnostics = new LinkedHashSet<>();

    public void add(String module, Diagnostic.Code code, String message) {
        diagnostics.add(new Diagnostic(module, code, message));
    }

    public List<Diagnostic> toList() {
        return new ArrayList<>(diagnostics);
    }
}
