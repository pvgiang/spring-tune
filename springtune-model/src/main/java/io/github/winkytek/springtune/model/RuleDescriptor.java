package io.github.winkytek.springtune.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static metadata about a rule. Shared by the CI check, the runtime starter and (later) the controller,
 * so the same ID means the same thing everywhere.
 *
 * @param id              stable identifier, e.g. {@code ST-HIKARI-002}
 * @param title           short human title
 * @param defaultSeverity severity used unless overridden in {@code springtune.yml}
 * @param category        grouping, e.g. {@code hikari}, {@code async}
 * @param summary         one-sentence description of what the rule detects
 */
public record RuleDescriptor(String id, String title, Severity defaultSeverity, String category, String summary) {

    private static final Pattern ID = Pattern.compile("ST-[A-Z0-9]+(-[A-Z0-9]+)*-\\d{3}");
    public static final String DOCS_BASE = "https://github.com/pvgiang/spring-tune/blob/main/docs/rules/";

    public RuleDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(summary, "summary");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Rule id '" + id + "' must match " + ID.pattern());
        }
    }

    public String docsUrl() {
        return DOCS_BASE + id + ".md";
    }
}
