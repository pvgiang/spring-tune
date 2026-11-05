package io.github.winkytek.springtune.loadtests;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.SpringBootVersion;
import org.testcontainers.DockerClientFactory;

/**
 * Runs a suite only with a PostgreSQL to run it against: an external server ({@code -Dspringtune.it.postgres.url})
 * or Docker for Testcontainers. Without either the suite is skipped, unless
 * {@code -Dspringtune.it.requireDocker=true}, which turns the absence into a failure (CI). Suites run on Spring
 * Boot 4.x only (the Boot 3.5 build compiles them and skips them).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresPostgres.Condition.class)
@interface RequiresPostgres {

    final class Condition implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String boot = SpringBootVersion.getVersion();
            if (boot != null && !boot.startsWith("4.")) {
                return ConditionEvaluationResult.disabled("Load suites run on Spring Boot 4.x only (this build: "
                        + boot + ")");
            }
            if (PostgresBackend.externalConfigured()) {
                return ConditionEvaluationResult.enabled("external PostgreSQL " + System.getProperty(PostgresBackend.URL));
            }
            boolean docker;
            try {
                docker = DockerClientFactory.instance().isDockerAvailable();
            } catch (RuntimeException ex) {
                docker = false;
            }
            if (docker) {
                return ConditionEvaluationResult.enabled("Docker available: Testcontainers " + PostgresBackend.IMAGE);
            }
            if (Boolean.getBoolean(PostgresBackend.REQUIRE_DOCKER)) {
                throw new IllegalStateException("-D" + PostgresBackend.REQUIRE_DOCKER + "=true but Docker is not"
                        + " available and -D" + PostgresBackend.URL + " is not set");
            }
            return ConditionEvaluationResult.disabled("No PostgreSQL: set -D" + PostgresBackend.URL
                    + " or provide Docker (-D" + PostgresBackend.REQUIRE_DOCKER + "=true makes this a failure)");
        }
    }
}
