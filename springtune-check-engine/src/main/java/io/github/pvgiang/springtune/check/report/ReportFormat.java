package io.github.pvgiang.springtune.check.report;

import io.github.pvgiang.springtune.check.CheckException;
import java.util.Locale;

public enum ReportFormat {
    TEXT("springtune-report.txt"),
    MARKDOWN("springtune-report.md"),
    JSON("springtune-report.json"),
    SARIF("springtune-report.sarif");

    private final String fileName;

    ReportFormat(String fileName) {
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    public static ReportFormat parse(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("MD")) {
            return MARKDOWN;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new CheckException("Unknown report format '" + value + "'; expected text, markdown, json or sarif");
        }
    }
}
