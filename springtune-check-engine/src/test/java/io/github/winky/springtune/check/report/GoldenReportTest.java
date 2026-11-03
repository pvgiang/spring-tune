package io.github.winky.springtune.check.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.winky.springtune.check.CheckEngine;
import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.CheckRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * End-to-end over a realistic two-service fixture. The expected file was reviewed by hand; regenerate it
 * with {@code -Dspringtune.updateGolden=true} only after reviewing the diff.
 */
class GoldenReportTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/shop");
    private static final Path EXPECTED = Path.of("src/test/resources/fixtures/shop-expected-report.json");

    @Test
    void shopFixtureMatchesGoldenReport() throws Exception {
        CheckReport report = new CheckEngine().run(CheckRequest.of(FIXTURE));
        String actual = Reporters.render(report, ReportFormat.JSON);
        if (Boolean.getBoolean("springtune.updateGolden") || !Files.exists(EXPECTED)) {
            Files.writeString(EXPECTED, actual);
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expectedTree = mapper.readTree(Files.readString(EXPECTED));
        JsonNode actualTree = mapper.readTree(actual);
        ((ObjectNode) expectedTree).remove("version");
        ((ObjectNode) actualTree).remove("version");
        assertThat(actualTree).isEqualTo(expectedTree);
    }
}
