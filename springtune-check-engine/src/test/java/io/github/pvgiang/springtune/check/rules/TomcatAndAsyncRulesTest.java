package io.github.pvgiang.springtune.check.rules;

import static io.github.pvgiang.springtune.check.testing.Checks.findings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.pvgiang.springtune.check.testing.TestProject;
import io.github.pvgiang.springtune.model.Severity;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomcatAndAsyncRulesTest {

    @TempDir
    Path root;

    private TestProject webJdbc(String yml) {
        return TestProject.in(root).pom(".", "spring-boot-starter-web", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", yml);
    }

    @Test
    void tomcatDefaultsAreInfo() {
        webJdbc("a: 1\n");
        assertThat(findings(root, "ST-TOMCAT-001")).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.message()).contains("200 request threads (default)", "10 pooled connections (default)");
        });
    }

    @Test
    void tomcatExplicitMismatchIsWarningAtThreadsLine() {
        webJdbc("spring.datasource.hikari.maximum-pool-size: 20\nserver:\n  tomcat:\n    threads:\n      max: 400\n");
        assertThat(findings(root, "ST-TOMCAT-001")).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.location().line()).isEqualTo(5);
        });
    }

    @Test
    void tomcatBalancedPasses() {
        webJdbc("spring.datasource.hikari.maximum-pool-size: 20\nserver.tomcat.threads.max: 100\n");
        assertThat(findings(root, "ST-TOMCAT-001")).isEmpty();
    }

    @Test
    void tomcatSkippedWithVirtualThreadsOrJetty() {
        webJdbc("spring.threads.virtual.enabled: true\n");
        assertThat(findings(root, "ST-TOMCAT-001")).isEmpty();
        TestProject.in(root).pom(".", "spring-boot-starter-web", "spring-boot-starter-jetty", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "a: 1\n");
        assertThat(findings(root, "ST-TOMCAT-001")).isEmpty();
        TestProject.in(root).pom(".", "spring-boot-starter-web", "spring-boot-starter-jdbc");
        assertThat(findings(root, "ST-TOMCAT-001")).as("control: same project on Tomcat").hasSize(1);
    }

    @Test
    void enableAsyncWithoutExecutorIsReportedAtAnnotation() {
        TestProject.in(root).pom(".", "spring-boot-starter").resource(".", "application.yml", "a: 1\n")
                .java(".", "App", """
                        package demo;

                        @org.springframework.scheduling.annotation.EnableAsync
                        @SpringBootApplication
                        class App { @Async void notifyLater() {} }
                        """);
        assertThat(findings(root, "ST-ASYNC-001")).singleElement().satisfies(f -> {
            assertThat(f.location().path()).isEqualTo("src/main/java/demo/App.java");
            assertThat(f.location().line()).isEqualTo(3);
        });
    }

    @Test
    void enableAsyncWithQueueCapacityPasses() {
        TestProject.in(root).resource(".", "application.yml", "spring.task.execution.pool.queue-capacity: 500\n")
                .java(".", "App", "package demo;\n@EnableAsync class App { @Async void run() {} }\n");
        assertThat(findings(root, "ST-ASYNC-001")).isEmpty();
    }

    @Test
    void enableAsyncWithAsyncConfigurerPasses() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@EnableAsync class App implements AsyncConfigurer { @Async void run() {} }\n");
        assertThat(findings(root, "ST-ASYNC-001")).isEmpty();
    }

    @Test
    void executorBeanWithoutQueueCapacityIsUnbounded() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n").java(".", "AsyncConfig", """
                package demo;
                @EnableAsync
                class AsyncConfig {
                    @Bean ThreadPoolTaskExecutor taskExecutor() {
                        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                        executor.setCorePoolSize(4);
                        executor.setMaxPoolSize(16);
                        return executor;
                    }
                    @Bean ThreadPoolTaskExecutor bounded() {
                        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                        executor.setQueueCapacity(100);
                        return executor;
                    }
                }
                """);
        assertThat(findings(root, "ST-ASYNC-001")).isEmpty();
        assertThat(findings(root, "ST-ASYNC-002")).singleElement().satisfies(f -> {
            assertThat(f.subject()).isEqualTo("AsyncConfig#taskExecutor:ThreadPoolTaskExecutor");
            assertThat(f.location().line()).isEqualTo(5);
        });
    }

    @Test
    void executorsFactoriesAndUnboundedQueuesAreReported() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n").java(".", "Pools", """
                package demo;
                import java.util.concurrent.*;
                class Pools {
                    ExecutorService a = Executors.newFixedThreadPool(4);
                    ExecutorService b = java.util.concurrent.Executors.newCachedThreadPool();
                    ExecutorService c = new ThreadPoolExecutor(1, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
                    ExecutorService ok = new ThreadPoolExecutor(1, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
                    ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
                }
                """);
        assertThat(findings(root, "ST-ASYNC-002")).extracting(f -> f.location().line()).containsExactly(4, 5, 6);
    }
}
