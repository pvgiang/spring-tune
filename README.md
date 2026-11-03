# spring-tune

Spring Boot config safety and self-tuning.

Catch the Spring Boot configuration that will take you down before production does. Then let it tune itself when you're ready.

SpringTune has two parts that share one model and one set of rule IDs:

| Part | When it runs | What it does |
|---|---|---|
| **`springtune check`** (CLI, Maven plugin, GitHub Action) | CI, before deploy | Static analysis of `application*.yml/properties` (resolved exactly like Spring Boot does), Java sources and Helm/Kubernetes manifests. 13 rules for pool exhaustion, hung threads and retry storms. Text/Markdown/JSON/SARIF output; fails the build at a threshold. |
| **`springtune-spring-boot-starter`** | Runtime | `/actuator/tuning` with live knobs (HikariCP, `ThreadPoolTaskExecutor`), signals and **explained, budget-capped recommendations**. Read-only: it never changes anything. |

## Quick start

**GitHub Action** (findings show up as code-scanning alerts on the exact line):

```yaml
# .github/workflows/springtune.yml
on: [pull_request]
permissions: { contents: read, security-events: write }
jobs:
  springtune:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pvgiang/spring-tune@v0.1.0
        id: springtune
        with:
          profile: prod          # optional
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: ${{ steps.springtune.outputs.sarif }}
```

**CLI** (any build tool, Java 17+), from the [latest release](https://github.com/pvgiang/spring-tune/releases):

```bash
curl -fsSLO https://github.com/pvgiang/spring-tune/releases/download/v0.1.0/springtune-cli-0.1.0.jar
java -jar springtune-cli-0.1.0.jar check path/to/repo --profile prod
# exit 0 = clean, 1 = findings at/above --fail-on (default ERROR), 2 = usage/config error
```

**Maven plugin and runtime starter** (`io.github.winky.springtune:*:0.1.0`). Until Maven Central publishing
is set up, unzip `springtune-maven-repo-0.1.0.zip` from the release and add it as a file repository:

```xml
<pluginRepositories>
  <pluginRepository><id>springtune</id><url>file:///path/to/springtune-maven-repo-0.1.0</url></pluginRepository>
</pluginRepositories>
<!-- ... -->
<plugin>
  <groupId>io.github.winky.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

```xml
<dependency>
  <groupId>io.github.winky.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```yaml
management.endpoints.web.exposure.include: health,tuning
springtune.budget:            # optional, caps pool recommendations: floor(100 x 0.8 / 6) = 13
  database-max-connections: 100
  max-replicas: 6
```

## Case study: scanning 20 public Spring Boot projects

We ran `springtune check` with default settings over 20 well-known open-source Spring Boot repositories:
reference apps (Spring PetClinic, RealWorld, JHipster sample), microservice suites (petclinic-microservices,
PiggyMetrics, FTGO), admin platforms (mall, RuoYi, JeecgBoot, eladmin, litemall) and large products (Apache
ShenYu, Apache DolphinScheduler, ThingsBoard, Halo). In total: **282 Spring Boot modules, Spring Boot 1.3 to
4.1, Maven and Gradle**.

| | |
|---|---|
| Findings | **199** across 18 of 20 repositories |
| False positives (hand-reviewed) | **1 (0.5%)**; the plan's target was under 10% |
| Not evaluated (config not in the repo, e.g. Config Server or Nacos) | reported with the reason, never guessed |
| Runtime for all 20 repositories | about 70 seconds |

**What it found**, a few examples:

- **HTTP calls that can hang forever.** Spring PetClinic Microservices' API gateway calls other services
  with `@LoadBalanced new RestTemplate()`, and its GenAI service uses `RestClient.builder().build()`. Neither
  has a connect or read timeout, so one stuck downstream service can stall every request thread. Halo's UI
  proxy uses `WebClient.create(...)` with no response timeout (ST-HTTP-001).
- **Unbounded work queues behind user-facing features.** JeecgBoot streams AI chat (SSE) and vectorizes
  documents on `Executors.newFixedThreadPool(10)`, whose queue grows without limit under load (ST-ASYNC-002).
- **Pool waits of 30 seconds and leaks nobody would notice.** Apache DolphinScheduler's HikariCP-backed
  services, like those in 12 of the 20 repositories, keep `connectionTimeout` at the 30 s default and leak detection off
  (ST-HIKARI-001/002). When the pool runs dry, each request blocks for 30 s before failing.
- **Unbounded `@Async`.** litemall sends notifications through `@Async` on Spring Boot's default executor,
  which has an unbounded queue (ST-ASYNC-001).

**What the corpus taught the tool.** The first run produced 307 findings, of which at least 61 (20%) were
wrong: Druid-pooled apps flagged for HikariCP settings, Spring Boot 1.x apps (whose default pool is
tomcat-jdbc), `@EnableAsync` with no `@Async` methods, Feign clients whose timeouts come from Ribbon or a
config server. Each class became a regression test, and the check now also follows in-repo library modules
through the Maven/Gradle dependency graph.

Full method, per-rule precision, commit SHAs and raw reports:
[corpus report](docs/corpus/2026-11-corpus-report.md). Rerun it with `scripts/corpus/run-corpus.sh`.

## Try it on the sample

A deliberately broken app with both parts wired in:

```bash
mvn -q install -DskipTests -Dinvoker.skip
java -jar springtune-check-cli/target/springtune-check-cli-*-all.jar check springtune-samples/broken-config
java -jar springtune-samples/broken-config/target/springtune-sample-broken-config-*.jar
curl localhost:8080/actuator/tuning
```

## Build and verify

```bash
mvn -B verify                                              # all modules, unit + integration tests, plugin ITs
mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip  # Spring Boot 3.5 compatibility
```

Requires JDK 17+ and Maven 3.9+. CI (`.github/workflows/ci.yml`) runs JDK 17/21/25 and Boot 4.1 and 3.5,
and runs the GitHub Action against the sample. Releases: push a `vX.Y.Z` tag; `.github/workflows/release.yml`
builds, tests and publishes the GitHub Release with notes from `docs/releases/`.

## Modules

| Module | Purpose |
|---|---|
| `springtune-model` | Shared domain: `Finding`, `RuleDescriptor`, `Knob`, `Recommendation`, `ConnectionBudget` |
| `springtune-check-engine` | Config resolution, scanners, rule API, 13 rules, reporters |
| `springtune-check-cli` | `springtune check` / `springtune rules` (runnable `-all.jar`) |
| `springtune-maven-plugin` | `mvn springtune:check`, bound to `verify` |
| `springtune-spring-boot-autoconfigure` / `-starter` | Runtime registry, sampler, recommendations, `/actuator/tuning` |
| `springtune-samples/broken-config` | Runnable demo and end-to-end fixture |
| `action.yml` | GitHub Action wrapping the CLI |

## Docs

- [Delivery brief: scope, decisions, acceptance criteria](docs/delivery/iteration-1-brief.md)
- [Architecture](docs/architecture.md)
- [The check: CLI, Maven plugin, how config is resolved, limitations](docs/check.md)
- [`springtune.yml` reference](docs/configuration.md)
- [Rules](docs/rules/README.md)
- [Corpus run: 20 public repositories, false-positive rate](docs/corpus/2026-11-corpus-report.md)
- [Runtime starter](docs/starter.md)
- [CI integration (GitHub code scanning)](docs/ci-integration.md)
- [Release notes](docs/releases/)
- [Business & implementation plan](docs/business-and-implementation-plan.md)
