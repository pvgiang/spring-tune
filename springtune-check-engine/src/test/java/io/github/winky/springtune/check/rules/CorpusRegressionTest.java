package io.github.winky.springtune.check.rules;

import static io.github.winky.springtune.check.testing.Checks.findings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.winky.springtune.check.CheckReport;
import io.github.winky.springtune.check.Diagnostic;
import io.github.winky.springtune.check.testing.Checks;
import io.github.winky.springtune.check.testing.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * False-positive classes found in the 20-repository corpus run (docs/corpus/), each reduced to the
 * smallest project that reproduces it. Every test names the repository pattern it came from.
 */
class CorpusRegressionTest {

    @TempDir
    Path root;

    private static final String[] HIKARI_RULES = {"ST-HIKARI-001", "ST-HIKARI-002", "ST-TOMCAT-001"};

    private void assertNoHikariFindings(CheckReport report) {
        for (String rule : HIKARI_RULES) {
            assertThat(findings(report, rule)).as(rule).isEmpty();
        }
    }

    private TestProject bootPom(String version, String... artifacts) {
        StringBuilder deps = new StringBuilder();
        for (String artifact : artifacts) {
            deps.append("<dependency><artifactId>").append(artifact).append("</artifactId></dependency>");
        }
        return TestProject.in(root).file("pom.xml", """
                <project><parent><artifactId>spring-boot-starter-parent</artifactId><version>%s</version></parent>
                <dependencies>%s</dependencies></project>
                """.formatted(version, deps));
    }

    @Test
    void druidStarterOwnsTheDataSource() { // macrozheng/mall, litemall, eladmin
        bootPom("3.5.14", "spring-boot-starter-web", "spring-boot-starter-jdbc", "druid-spring-boot-3-starter")
                .resource(".", "application.yml", "spring.datasource.url: jdbc:mysql://db/mall\n");
        CheckReport report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(findings(report, "ST-TX-001")).as("transaction timeouts apply to any pool").hasSize(1);
    }

    @Test
    void druidOrDynamicDatasourcePropertiesWithoutStarterInBuild() { // paascloud, jeecg-style configs
        bootPom("2.7.18", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "spring.datasource.druid.max-active: 20\n");
        assertNoHikariFindings(Checks.run(root));
        TestProject.in(root).resource(".", "application.yml",
                "spring.datasource.dynamic.datasource.master.url: jdbc:mysql://db/a\n");
        assertNoHikariFindings(Checks.run(root));
    }

    @Test
    void springBoot1DefaultsToTomcatJdbc() { // ityouknow/spring-boot-examples, paascloud
        bootPom("1.5.13.RELEASE", "spring-boot-starter-web", "spring-boot-starter-data-jpa")
                .resource(".", "application.yml", "spring.datasource.url: jdbc:mysql://db/a\n");
        assertNoHikariFindings(Checks.run(root));
        bootPom("1.5.13.RELEASE", "spring-boot-starter-web", "spring-boot-starter-data-jpa", "HikariCP");
        assertThat(findings(Checks.run(root), "ST-HIKARI-001")).as("explicit HikariCP on Boot 1.x").hasSize(1);
    }

    @Test
    void dataSourceBuiltInCodeSkipsPropertyRules() { // xkcoding demo-orm-jpa, dolphinscheduler
        bootPom("2.1.0.RELEASE", "spring-boot-starter-web", "spring-boot-starter-data-jpa")
                .resource(".", "application.yml", "spring.datasource.url: jdbc:mysql://db/a\n")
                .java(".", "JpaConfig", """
                        package demo;
                        class JpaConfig {
                            @Bean public DataSource dataSource() { return DataSourceBuilder.create().build(); }
                        }
                        """);
        CheckReport report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("builds its own DataSource"));
    }

    @Test
    void relocatedConfigFilesSkipMissingPropertyRules() { // thingsboard: --spring.config.name=tb-http-integration
        bootPom("3.5.15", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "tb-http-integration.yml", "spring.datasource.hikari.connection-timeout: 2000\n")
                .java(".", "App", """
                        package demo;
                        @SpringBootApplication
                        class App {
                            static final String KEY = "--spring.config.name";
                            Object client = new RestTemplate();
                        }
                        """);
        CheckReport report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(findings(report, "ST-TX-001")).isEmpty();
        assertThat(findings(report, "ST-HTTP-001")).as("source rules still apply").hasSize(1);
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo(Diagnostic.Code.UNSUPPORTED));
    }

    private TestProject mavenModule(String dir, String artifactId, String... dependencies) {
        StringBuilder deps = new StringBuilder();
        for (String dependency : dependencies) {
            deps.append("<dependency><artifactId>").append(dependency).append("</artifactId></dependency>");
        }
        return TestProject.in(root).file(dir + "/pom.xml",
                "<project><artifactId>" + artifactId + "</artifactId><dependencies>" + deps + "</dependencies></project>");
    }

    @Test
    void enableAsyncWithoutAsyncMethodsIsSilent() { // thingsboard integrations
        TestProject.in(root).resource("app", "application.yml", "a: 1\n")
                .java("app", "App", "package demo;\n@EnableAsync @SpringBootApplication class App {}\n");
        assertThat(findings(Checks.run(root), "ST-ASYNC-001")).isEmpty();
    }

    @Test
    void asyncMethodsAndExecutorsInLibraryModulesAreFollowedTransitively() { // eladmin: AsyncConfigurer in eladmin-common
        mavenModule("app", "app", "service");
        mavenModule("service", "service", "common");
        mavenModule("common", "common");
        TestProject.in(root).resource("app", "application.yml", "a: 1\n")
                .java("app", "App", "package demo;\n@EnableAsync @SpringBootApplication class App {}\n")
                .java("service", "Mailer", "package demo;\nclass Mailer { @Async void send() {} }\n");
        assertThat(findings(Checks.run(root), "ST-ASYNC-001")).as("@Async reached through app -> service")
                .singleElement().satisfies(f -> assertThat(f.module()).isEqualTo("app"));

        TestProject.in(root).java("common", "AsyncExecutor",
                "package demo;\nclass AsyncExecutor implements AsyncConfigurer {}\n");
        assertThat(findings(Checks.run(root), "ST-ASYNC-001")).as("AsyncConfigurer reached through service -> common")
                .isEmpty();
    }

    @Test
    void unrelatedModulesAreNotFollowed() {
        mavenModule("app", "app");
        mavenModule("other", "other");
        TestProject.in(root).resource("app", "application.yml", "a: 1\n")
                .java("app", "App", "package demo;\n@EnableAsync class App { @Async void run() {} }\n")
                .java("other", "AsyncExecutor", "package demo;\nclass AsyncExecutor implements AsyncConfigurer {}\n");
        assertThat(findings(Checks.run(root), "ST-ASYNC-001")).as("app does not depend on other").hasSize(1);
    }

    @Test
    void dataSourceBeanInLibraryModuleSkipsHikariRules() { // RuoYi: DruidConfig in ruoyi-framework
        TestProject.in(root).file("app/pom.xml", "<project><artifactId>app</artifactId><dependencies>"
                        + "<dependency><artifactId>spring-boot-starter-jdbc</artifactId></dependency>"
                        + "<dependency><artifactId>framework</artifactId></dependency></dependencies></project>")
                .resource("app", "application.yml", "a: 1\n")
                .java("framework", "DbConfig", "package demo;\nclass DbConfig { @Bean DataSource ds() { return null; } }\n");
        mavenModule("framework", "framework");
        assertNoHikariFindings(Checks.run(root));
    }

    @Test
    void enableAsyncBeforeBoot21DescribesSimpleAsyncTaskExecutor() { // piggymetrics-era apps
        bootPom("2.0.3.RELEASE", "spring-boot-starter-web").resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@EnableAsync class App { @Async void run() {} }\n");
        assertThat(findings(Checks.run(root), "ST-ASYNC-001")).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("SimpleAsyncTaskExecutor", "new thread for every task"));
    }

    @Test
    void legacyTomcatMaxThreadsKeyIsRead() { // Spring Boot <= 2.2 projects
        bootPom("2.1.0.RELEASE", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "server.tomcat.max-threads: 50\n");
        assertThat(findings(Checks.run(root), "ST-TOMCAT-001")).isEmpty();
    }

    @Test
    void mavenResourceFilterTokensAreUnresolvedNotParseErrors() { // paascloud: profiles.active: @profileActive@
        bootPom("1.5.13.RELEASE", "spring-boot-starter-web", "HikariCP").resource(".", "application.yml", """
                spring:
                  profiles:
                    active: @profileActive@
                  datasource:
                    hikari:
                      connection-timeout: @db.timeout@
                """);
        CheckReport report = Checks.run(root);
        assertThat(report.diagnostics()).noneSatisfy(d -> assertThat(d.code()).isEqualTo(Diagnostic.Code.PARSE_ERROR));
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("Active profile '${profileActive}'"));
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message())
                .contains("connection-timeout", "application.yml:6"));
        assertThat(findings(report, "ST-HIKARI-001")).isEmpty();
        assertThat(report.modules().get(0).activeProfiles()).containsExactly("default");
    }

    @Test
    void java22UnnamedVariablesParse() { // thingsboard DefaultIntegrationApiService
        TestProject.in(root).resource(".", "application.yml", "a: 1\n").java(".", "C", """
                package demo;
                class C { void m(java.util.List<String> l) { for (String _ : l) {} Object t = new RestTemplate(); } }
                """);
        CheckReport report = Checks.run(root);
        assertThat(report.diagnostics()).noneSatisfy(d -> assertThat(d.code()).isEqualTo(Diagnostic.Code.PARSE_ERROR));
        assertThat(findings(report, "ST-HTTP-001")).hasSize(1);
    }

    @Test
    void remoteConfigSkipsMissingPropertyRules() { // piggymetrics (Config Server), JeecgBoot (Nacos import)
        bootPom("2.7.18", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "a: 1\n")
                .resource(".", "bootstrap.yml", "spring:\n  cloud:\n    config:\n      uri: http://config:8888\n");
        CheckReport report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(findings(report, "ST-TX-001")).isEmpty();
        assertThat(report.diagnostics()).anySatisfy(d ->
                assertThat(d.message()).contains("Spring Cloud Config Server (bootstrap config)"));

        TestProject.in(root).file("src/main/resources/bootstrap.yml", "a: 1\n")
                .resource(".", "application.yml", "spring.config.import: optional:nacos:shop.yaml\n");
        report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(report.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("optional:nacos:shop.yaml"));

        TestProject.in(root).resource(".", "application.yml", "spring.config.import: optional:file:./extra.yml\n");
        assertThat(findings(Checks.run(root), "ST-HIKARI-001")).as("local file imports are not remote").hasSize(1);
    }

    @Test
    void ribbonEraFeignIsGovernedByRibbonTimeouts() { // piggymetrics, paascloud
        bootPom("2.0.3.RELEASE", "spring-cloud-starter-openfeign", "spring-cloud-starter-netflix-eureka-client")
                .resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@EnableFeignClients class App {}\n");
        assertThat(findings(Checks.run(root), "ST-HTTP-002")).isEmpty();
        bootPom("2.7.18", "spring-cloud-starter-openfeign", "spring-cloud-starter-netflix-eureka-client");
        assertThat(findings(Checks.run(root), "ST-HTTP-002")).as("Spring Cloud LoadBalancer era").hasSize(1);
    }

    @Test
    void excludedDataSourceAutoConfigurationSkipsHikariRules() { // RuoYi: @SpringBootApplication(exclude = ...)
        bootPom("3.5.14", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })"
                        + " class App {}\n");
        CheckReport report = Checks.run(root);
        assertNoHikariFindings(report);
        assertThat(findings(report, "ST-TX-001")).hasSize(1);
        TestProject.in(root).java(".", "App", "package demo;\n@SpringBootApplication class App {}\n")
                .resource(".", "application.yml", "spring.autoconfigure.exclude: "
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration\n");
        assertNoHikariFindings(Checks.run(root));
    }

    @Test
    void feignArtifactWithoutEnableFeignClientsIsNotFeign() { // apache/shenyu sdk-feign example (@EnableShenyuClients)
        bootPom("3.3.1", "spring-cloud-starter-openfeign", "spring-boot-starter-web")
                .resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@EnableShenyuClients @SpringBootApplication class App {}\n");
        assertThat(findings(Checks.run(root), "ST-HTTP-002")).isEmpty();
    }
}
