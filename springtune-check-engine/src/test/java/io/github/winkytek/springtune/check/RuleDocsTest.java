package io.github.winkytek.springtune.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.model.RuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Quality bar (plan §20): every rule has a docs page that names it and explains why and how to fix. */
class RuleDocsTest {

    private static final Path DOCS = Path.of("../docs/rules");

    @Test
    void everyRuleHasADocsPage() throws Exception {
        for (RuleDescriptor rule : new CheckEngine().descriptors()) {
            Path page = DOCS.resolve(rule.id() + ".md");
            assertThat(page).as("docs page for " + rule.id()).exists();
            String content = Files.readString(page);
            assertThat(content).startsWith("# " + rule.id() + ": " + rule.title());
            assertThat(content).contains("## Why it matters", "## Example", "## Suppress");
            assertThat(Files.readString(DOCS.resolve("README.md"))).contains("[" + rule.id() + "]");
        }
    }
}
