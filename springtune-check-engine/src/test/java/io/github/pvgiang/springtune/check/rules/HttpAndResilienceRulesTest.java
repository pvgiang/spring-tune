package io.github.pvgiang.springtune.check.rules;

import static io.github.pvgiang.springtune.check.testing.Checks.findings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.pvgiang.springtune.check.testing.TestProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpAndResilienceRulesTest {

    @TempDir
    Path root;

    @Test
    void httpClientsWithoutTimeoutsAreReported() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n").java(".", "Clients", """
                package demo;
                class Clients {
                    RestTemplate a = new RestTemplate();
                    RestClient b = RestClient.create("http://inventory");
                    RestClient c = RestClient.builder().baseUrl("http://x").build();
                    WebClient d = WebClient.create();
                    WebClient e = WebClient.builder().baseUrl("http://y").build();
                    RestTemplate f = new RestTemplateBuilder().rootUri("http://z").build();
                }
                """);
        assertThat(findings(root, "ST-HTTP-001")).extracting(f -> f.location().line())
                .containsExactly(3, 4, 5, 6, 7, 8);
    }

    @Test
    void configuredOrInjectedClientsPass() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n").java(".", "Clients", """
                package demo;
                class Clients {
                    Clients(RestClient.Builder injected, RestTemplateBuilder templates, WebClient.Builder web) {
                        RestClient a = injected.baseUrl("http://x").build();
                        RestTemplate b = templates.build();
                        WebClient c = web.build();
                    }
                    RestTemplate d = new RestTemplate(factory());
                    RestClient e = RestClient.builder().requestFactory(factory()).build();
                    WebClient f = WebClient.builder().clientConnector(connector()).build();
                    RestTemplate g = new RestTemplateBuilder().connectTimeout(Duration.ofSeconds(1)).build();
                    RestClient h = RestClient.create(existingTemplate);
                }
                """);
        assertThat(findings(root, "ST-HTTP-001")).isEmpty();
    }

    @Test
    void feignWithoutReadTimeoutIsReported() {
        TestProject.in(root).resource(".", "application.yml", "a: 1\n")
                .java(".", "App", "package demo;\n@EnableFeignClients\nclass App {}\n");
        assertThat(findings(root, "ST-HTTP-002")).singleElement()
                .satisfies(f -> assertThat(f.location().path()).endsWith("App.java"));
    }

    @Test
    void feignWithReadTimeoutPassesIncludingLegacyKeys() {
        TestProject.in(root)
                .resource(".", "application.yml", "feign:\n  client:\n    config:\n      inventory:\n        readTimeout: 2000\n")
                .java(".", "App", "package demo;\n@EnableFeignClients\nclass App {}\n");
        assertThat(findings(root, "ST-HTTP-002")).isEmpty();
    }

    @Test
    void transactionTimeoutMissingIsInfo() {
        TestProject.in(root).pom(".", "spring-boot-starter-jdbc").resource(".", "application.yml", "a: 1\n");
        assertThat(findings(root, "ST-TX-001")).hasSize(1);
    }

    @Test
    void transactionTimeoutByPropertyOrAnnotationPasses() {
        TestProject.in(root).pom(".", "spring-boot-starter-jdbc")
                .resource(".", "application.yml", "spring.transaction.default-timeout: 30s\n");
        assertThat(findings(root, "ST-TX-001")).isEmpty();
        TestProject.in(root).resource(".", "application.yml", "a: 1\n")
                .java(".", "Svc", "package demo;\nclass Svc { @Transactional(timeout = 5) void run() {} }\n");
        assertThat(findings(root, "ST-TX-001")).isEmpty();
    }

    @Test
    void retryWithoutBackoffIsReportedPerInstance() {
        TestProject.in(root).resource(".", "application.yml", """
                resilience4j:
                  retry:
                    configs:
                      jittered:
                        enable-randomized-wait: true
                    instances:
                      payments:
                        max-attempts: 5
                      inventory:
                        base-config: jittered
                      single:
                        max-attempts: 1
                      exp:
                        enableExponentialBackoff: true
                """);
        assertThat(findings(root, "ST-R4J-001")).singleElement().satisfies(f -> {
            assertThat(f.subject()).isEqualTo("resilience4j.retry.instances.payments");
            assertThat(f.message()).contains("up to 5 attempts");
            assertThat(f.location().line()).isEqualTo(8);
        });
    }

    @Test
    void retryInheritsFromDefaultConfig() {
        TestProject.in(root).resource(".", "application.yml", """
                resilience4j.retry:
                  configs:
                    default:
                      enable-exponential-backoff: true
                  instances:
                    payments:
                      max-attempts: 5
                """);
        assertThat(findings(root, "ST-R4J-001")).isEmpty();
    }

    @Test
    void circuitBreakerWithoutSlowCallDetectionIsReported() {
        TestProject.in(root).resource(".", "application.yml", """
                resilience4j:
                  circuitbreaker:
                    instances:
                      blind:
                        failure-rate-threshold: 50
                      rate100:
                        slow-call-duration-threshold: 2s
                      ok:
                        slow-call-duration-threshold: 2s
                        slow-call-rate-threshold: 50
                      limited:
                        failure-rate-threshold: 50
                  timelimiter:
                    instances:
                      limited:
                        timeout-duration: 2s
                """);
        assertThat(findings(root, "ST-R4J-002")).extracting(f -> f.subject()).containsExactly(
                "resilience4j.circuitbreaker.instances.blind", "resilience4j.circuitbreaker.instances.rate100");
    }
}
