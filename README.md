# spring-tune

Spring Boot config safety and self-tuning.

Catch the Spring Boot configuration that will take you down before production does. Then let it tune itself when you're ready.

SpringTune has two parts that share one model and one set of rule IDs:

| Part | When it runs | What it does |
|---|---|---|
| **`springtune check`** (CLI and Maven plugin) | CI, before deploy | Static analysis of `application*.yml/properties` (resolved exactly like Spring Boot does), Java sources and Helm/Kubernetes manifests. 13 rules for pool exhaustion, hung threads and retry storms. Text/Markdown/JSON/SARIF output; fails the build at a threshold. |
| **`springtune-spring-boot-starter`** | Runtime | `/actuator/tuning` with live knobs (HikariCP, `ThreadPoolTaskExecutor`), signals and **explained, budget-capped recommendations**. Read-only: it never changes anything. |

## Quick start

**CLI** (any build tool):

```bash
mvn -q package -DskipTests -Dinvoker.skip -pl springtune-check-cli -am
java -jar springtune-check-cli/target/springtune-check-cli-0.1.0-SNAPSHOT-all.jar check path/to/repo --profile prod
# exit 0 = clean, 1 = findings at/above --fail-on (default ERROR), 2 = usage/config error
```

**Maven plugin:**

```xml
<plugin>
  <groupId>io.github.pvgiang.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

**Runtime starter:**

```xml
<dependency>
  <groupId>io.github.pvgiang.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
management.endpoints.web.exposure.include: health,tuning
springtune.budget:            # optional, caps pool recommendations: floor(100 x 0.8 / 6) = 13
  database-max-connections: 100
  max-replicas: 6
```

**Try it on the sample** (deliberately broken app):

```bash
mvn -q install -DskipTests -Dinvoker.skip
java -jar springtune-check-cli/target/springtune-check-cli-0.1.0-SNAPSHOT-all.jar check springtune-samples/broken-config
java -jar springtune-samples/broken-config/target/springtune-sample-broken-config-0.1.0-SNAPSHOT.jar
curl localhost:8080/actuator/tuning
```

Measured on 20 public Spring Boot repositories (282 modules): 199 findings, **0.5% false positives**
([corpus report](docs/corpus/2026-11-corpus-report.md); rerun with `scripts/corpus/run-corpus.sh`).

## Build and verify

```bash
mvn -B verify                                              # all modules, unit + integration tests, plugin ITs
mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip  # Spring Boot 3.5 compatibility
```

Requires JDK 17+ and Maven 3.9+. CI (`.github/workflows/ci.yml`) runs JDK 17/21/25 and Boot 4.1 and 3.5.

## Modules

| Module | Purpose |
|---|---|
| `springtune-model` | Shared domain: `Finding`, `RuleDescriptor`, `Knob`, `Recommendation`, `ConnectionBudget` |
| `springtune-check-engine` | Config resolution, scanners, rule API, 13 rules, reporters |
| `springtune-check-cli` | `springtune check` / `springtune rules` (runnable `-all.jar`) |
| `springtune-maven-plugin` | `mvn springtune:check`, bound to `verify` |
| `springtune-spring-boot-autoconfigure` / `-starter` | Runtime registry, sampler, recommendations, `/actuator/tuning` |
| `springtune-samples/broken-config` | Runnable demo and end-to-end fixture |

## Docs

- [Delivery brief: scope, decisions, acceptance criteria](docs/delivery/iteration-1-brief.md)
- [Architecture](docs/architecture.md)
- [The check: CLI, Maven plugin, how config is resolved, limitations](docs/check.md)
- [`springtune.yml` reference](docs/configuration.md)
- [Rules](docs/rules/README.md)
- [Corpus run: 20 public repositories, false-positive rate](docs/corpus/2026-11-corpus-report.md)
- [Runtime starter](docs/starter.md)
- [CI integration (GitHub code scanning)](docs/ci-integration.md)
- [Business & implementation plan](docs/business-and-implementation-plan.md)
