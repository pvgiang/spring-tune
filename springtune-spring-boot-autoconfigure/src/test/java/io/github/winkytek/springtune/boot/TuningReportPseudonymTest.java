package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Pseudonyms of pools that start after the first report (iteration 6 R4, iteration 7 row 10): an unnamed Hikari
 * pool has no pool name until it starts (its component id is {@code hikari:default}, then {@code hikari:HikariPool-n}),
 * so numbering by the current id moved it, and every pool sorted after it, to another {@code hikari#n}.
 */
class TuningReportPseudonymTest {

    /** Pseudonym of every pool, keyed by its maximumPoolSize (each pool in the test has its own). */
    private static Map<Long, String> refs(TuningService service) {
        TuningReport report = TuningReport.from(service.snapshot(), "0.4.3", "3.5.16", 17);
        Map<Long, String> byPool = new LinkedHashMap<>();
        report.components().forEach(c -> byPool.put(c.knobs().get("maximumPoolSize"), c.ref()));
        return byPool;
    }

    @Test
    void aPoolStartingAfterTheFirstReportKeepsItsPseudonymAndShiftsNoOther() throws Exception {
        HikariDataSource analytics = new HikariDataSource();
        analytics.setJdbcUrl("jdbc:h2:mem:pseudonyms-a");
        analytics.setPoolName("analytics");
        analytics.setMaximumPoolSize(3);
        HikariDataSource lazy = new HikariDataSource(); // unnamed and not started
        lazy.setJdbcUrl("jdbc:h2:mem:pseudonyms-b");
        lazy.setMaximumPoolSize(7);
        try {
            try (Connection c = analytics.getConnection()) {
                assertThat(c.isValid(1)).isTrue();
            }
            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            beans.registerSingleton("analyticsDataSource", analytics);
            beans.registerSingleton("lazyDataSource", lazy);
            TuningService service = new TuningService(new SpringTuneProperties(), beans, Clock.systemUTC());
            service.sample();
            assertThat(service.components()).extracting(TunedComponent::id)
                    .containsExactly("hikari:analytics", "hikari:default");
            Map<Long, String> first = refs(service);
            assertThat(first).containsEntry(3L, "hikari#1").containsEntry(7L, "hikari#2");

            try (Connection c = lazy.getConnection()) {
                assertThat(c.isValid(1)).isTrue();
            }
            service.sample();
            assertThat(service.components().get(1).id()).startsWith("hikari:HikariPool-");
            Map<Long, String> second = refs(service);

            assertThat(second).as("pseudonyms after the lazy pool started").isEqualTo(first);
        } finally {
            lazy.close();
            analytics.close();
        }
    }
}
