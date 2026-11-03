package io.github.winky.springtune.check.testing;

import io.github.winky.springtune.check.CheckEngine;
import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.CheckRequest;
import io.github.winky.springtune.model.Finding;
import java.nio.file.Path;
import java.util.List;

public final class Checks {

    private Checks() {
    }

    public static CheckReport run(Path root, String... profiles) {
        return new CheckEngine().run(new CheckRequest(root, List.of(), List.of(profiles), null));
    }

    public static List<Finding> findings(CheckReport report, String ruleId) {
        return report.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    public static List<Finding> findings(Path root, String ruleId) {
        return findings(run(root), ruleId);
    }
}
