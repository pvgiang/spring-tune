package io.github.winky.springtune.check.rules;

import static io.github.winky.springtune.check.testing.Checks.findings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.Diagnostic;
import io.github.winky.springtune.check.testing.Checks;
import io.github.winky.springtune.check.testing.TestProject;
import io.github.winky.springtune.model.Severity;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HikariRulesTest {

    @TempDir
    Path root;

    private TestProject jdbcApp(String applicationYml) {
        return TestProject.in(root).pom(".", "spring-boot-starter-data-jpa")
                .resource(".", "application.yml", applicationYml);
    }

    @Nested
    class ConnectionTimeout {

        @Test
        void defaultTimeoutIsReportedAtTheConfigFile() {
            jdbcApp("server:\n  port: 8080\n");
            assertThat(findings(root, "ST-HIKARI-001")).singleElement().satisfies(f -> {
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
                assertThat(f.message()).contains("30000 ms (default)");
                assertThat(f.location().path()).isEqualTo("src/main/resources/application.yml");
            });
        }

        @Test
        void zeroMeansInfiniteAndIsAnError() {
            jdbcApp("spring.datasource.hikari.connection-timeout: 0\n");
            assertThat(findings(root, "ST-HIKARI-001")).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.ERROR));
        }

        @Test
        void aboveThresholdIsReportedAtThePropertyLine() {
            jdbcApp("a: 1\nspring:\n  datasource:\n    hikari:\n      connection-timeout: 10000\n");
            assertThat(findings(root, "ST-HIKARI-001")).singleElement()
                    .satisfies(f -> assertThat(f.location().line()).isEqualTo(5));
        }

        @Test
        void shortTimeoutPasses() {
            jdbcApp("spring.datasource.hikari.connection-timeout: 3000\n");
            assertThat(findings(root, "ST-HIKARI-001")).isEmpty();
        }

        @Test
        void thresholdIsConfigurable() {
            jdbcApp("spring.datasource.hikari.connection-timeout: 10000\n")
                    .file("springtune.yml", "rules:\n  ST-HIKARI-001:\n    options:\n      max-ms: 15000\n");
            assertThat(findings(root, "ST-HIKARI-001")).isEmpty();
        }

        @Test
        void silentWithoutJdbc() {
            TestProject.in(root).pom(".", "spring-boot-starter-webflux").resource(".", "application.yml", "a: 1\n");
            assertThat(findings(root, "ST-HIKARI-001")).isEmpty();
        }

        @Test
        void silentForOtherPoolTypes() {
            jdbcApp("spring.datasource.type: org.apache.commons.dbcp2.BasicDataSource\n");
            assertThat(findings(root, "ST-HIKARI-001")).isEmpty();
        }

        @Test
        void unresolvedPlaceholderIsNotEvaluatedButDiagnosed() {
            jdbcApp("spring.datasource.hikari.connection-timeout: ${DB_TIMEOUT}\n");
            CheckReport report = Checks.run(root);
            assertThat(findings(report, "ST-HIKARI-001")).isEmpty();
            assertThat(report.diagnostics()).anySatisfy(d -> {
                assertThat(d.code()).isEqualTo(Diagnostic.Code.UNRESOLVED_VALUE);
                assertThat(d.message()).contains("connection-timeout");
            });
        }
    }

    @Nested
    class LeakDetection {

        @Test
        void missingIsReported() {
            jdbcApp("a: 1\n");
            assertThat(findings(root, "ST-HIKARI-002")).hasSize(1);
        }

        @Test
        void valueBelowHikariMinimumIsReported() {
            jdbcApp("spring.datasource.hikari.leak-detection-threshold: 1000\n");
            assertThat(findings(root, "ST-HIKARI-002")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("below 2000 ms"));
        }

        @Test
        void valueAboveMaxLifetimeIsReported() {
            jdbcApp("spring.datasource.hikari:\n  leak-detection-threshold: 60000\n  max-lifetime: 40000\n");
            assertThat(findings(root, "ST-HIKARI-002")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("above maxLifetime"));
        }

        @Test
        void sensibleValuePasses() {
            jdbcApp("spring.datasource.hikari.leak-detection-threshold: 60000\n");
            assertThat(findings(root, "ST-HIKARI-002")).isEmpty();
        }
    }

    @Nested
    class MaxLifetime {

        @Test
        void skippedWithDiagnosticWhenDatabaseIdleTimeoutUnknown() {
            jdbcApp("a: 1\n");
            CheckReport report = Checks.run(root);
            assertThat(findings(report, "ST-HIKARI-003")).isEmpty();
            assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).startsWith("ST-HIKARI-003"));
        }

        @Test
        void defaultLifetimeAboveDatabaseIdleTimeoutIsAnError() {
            jdbcApp("a: 1\n").file("springtune.yml",
                    "databases:\n  - name: main\n    maxConnections: 100\n    idleTimeout: 10m\n");
            assertThat(findings(root, "ST-HIKARI-003")).singleElement().satisfies(f -> {
                assertThat(f.severity()).isEqualTo(Severity.ERROR);
                assertThat(f.message()).contains("1800000 ms (default)", "600000 ms");
                assertThat(f.fix()).contains("570000 ms");
            });
        }

        @Test
        void tooSmallValueFallsBackToDefaultLikeHikari() {
            jdbcApp("spring.datasource.hikari.max-lifetime: 10000\n").file("springtune.yml",
                    "databases:\n  - name: main\n    maxConnections: 100\n    idleTimeout: 10m\n");
            assertThat(findings(root, "ST-HIKARI-003")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("below 30000 ms"));
        }

        @Test
        void infiniteLifetimeIsAnError() {
            jdbcApp("spring.datasource.hikari.max-lifetime: 0\n").file("springtune.yml",
                    "databases:\n  - name: main\n    maxConnections: 100\n    idleTimeout: 8h\n");
            assertThat(findings(root, "ST-HIKARI-003")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("never retire"));
        }

        @Test
        void lifetimeWithMarginPasses() {
            jdbcApp("a: 1\n").file("springtune.yml",
                    "databases:\n  - name: main\n    maxConnections: 100\n    idleTimeout: 8h\n");
            assertThat(findings(root, "ST-HIKARI-003")).isEmpty();
        }
    }

    @Nested
    class ConnectionBudget {

        @Test
        void exceededBudgetWithExplicitReplicas() {
            jdbcApp("spring.datasource.hikari.maximum-pool-size: 20\n").file("springtune.yml", """
                    databases:
                      - name: main
                        maxConnections: 100
                    services:
                      - path: .
                        maxReplicas: 5
                    """);
            assertThat(findings(root, "ST-HIKARI-004")).singleElement().satisfies(f -> {
                assertThat(f.severity()).isEqualTo(Severity.ERROR);
                assertThat(f.message()).contains("allows 80", "can open 100", ". 20x5");
                assertThat(f.fix()).contains("16");
                assertThat(f.location().line()).isEqualTo(1);
            });
        }

        @Test
        void withinBudgetPasses() {
            jdbcApp("spring.datasource.hikari.maximum-pool-size: 20\n").file("springtune.yml",
                    "databases:\n  - name: main\n    maxConnections: 100\nservices:\n  - path: .\n    maxReplicas: 4\n");
            assertThat(findings(root, "ST-HIKARI-004")).isEmpty();
        }

        @Test
        void replicasAreAutoDiscoveredFromASingleHpa() {
            jdbcApp("a: 1\n")
                    .file("springtune.yml", "databases:\n  - name: main\n    maxConnections: 50\n")
                    .file("k8s/hpa.yaml", """
                            apiVersion: autoscaling/v2
                            kind: HorizontalPodAutoscaler
                            spec:
                              minReplicas: 2
                              maxReplicas: 6
                            """);
            assertThat(findings(root, "ST-HIKARI-004")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("10x6", "k8s/hpa.yaml", "auto-discovered"));
        }

        @Test
        void helmValuesAutoscalingWinsOverReplicaCount() {
            jdbcApp("a: 1\n")
                    .file("springtune.yml", """
                            databases:
                              - name: main
                                maxConnections: 50
                            services:
                              - path: .
                                deployment: [chart/values.yaml]
                            """)
                    .file("chart/values.yaml", "replicaCount: 2\nautoscaling:\n  enabled: true\n  maxReplicas: 8\n");
            assertThat(findings(root, "ST-HIKARI-004")).singleElement()
                    .satisfies(f -> assertThat(f.message()).contains("10x8", "autoscaling.maxReplicas"));
        }

        @Test
        void servicesSharingADatabaseAreSummed() {
            TestProject.in(root)
                    .pom("orders", "spring-boot-starter-jdbc").resource("orders", "application.yml",
                            "spring.datasource.hikari.maximum-pool-size: 20\n")
                    .pom("billing", "spring-boot-starter-jdbc").resource("billing", "application.yml", "a: 1\n")
                    .file("springtune.yml", """
                            databases:
                              - name: shared
                                maxConnections: 100
                                safetyFactor: 1.0
                            services:
                              - path: orders
                                database: shared
                                maxReplicas: 4
                              - path: billing
                                database: shared
                                maxReplicas: 3
                            """);
            assertThat(findings(root, "ST-HIKARI-004")).hasSize(2).allSatisfy(f ->
                    assertThat(f.message()).contains("allows 100", "can open 110", "orders 20x4", "billing 10x3"));
        }

        @Test
        void unknownReplicasSkipsWithDiagnostic() {
            jdbcApp("a: 1\n").file("springtune.yml", "databases:\n  - name: main\n    maxConnections: 10\n");
            CheckReport report = Checks.run(root);
            assertThat(findings(report, "ST-HIKARI-004")).isEmpty();
            assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("max replicas unknown"));
        }

        @Test
        void ambiguousDeploymentFilesAreNotGuessed() {
            jdbcApp("a: 1\n")
                    .file("springtune.yml", "databases:\n  - name: main\n    maxConnections: 10\n")
                    .file("k8s/a.yaml", "kind: HorizontalPodAutoscaler\nspec:\n  maxReplicas: 3\n")
                    .file("k8s/b.yaml", "kind: HorizontalPodAutoscaler\nspec:\n  maxReplicas: 9\n");
            CheckReport report = Checks.run(root);
            assertThat(findings(report, "ST-HIKARI-004")).isEmpty();
            assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("Several deployment files"));
        }
    }

    @Nested
    class PoolSize {

        @Test
        void oversizedPoolIsReported() {
            jdbcApp("spring.datasource.hikari.maximum-pool-size: 80\n");
            assertThat(findings(root, "ST-HIKARI-005")).hasSize(1);
        }

        @Test
        void normalPoolPasses() {
            jdbcApp("spring.datasource.hikari.maximum-pool-size: 30\n");
            assertThat(findings(root, "ST-HIKARI-005")).isEmpty();
        }
    }
}
