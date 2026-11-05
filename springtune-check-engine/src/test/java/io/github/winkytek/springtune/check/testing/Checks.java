package io.github.winkytek.springtune.check.testing;

import io.github.winkytek.springtune.check.CheckEngine;
import io.github.winkytek.springtune.check.CheckReport;
import io.github.winkytek.springtune.check.CheckRequest;
import io.github.winkytek.springtune.model.Finding;
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
