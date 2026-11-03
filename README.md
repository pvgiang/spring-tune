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

## Case study: what we found in 20 popular Spring Boot projects

We ran `springtune check` over 20 well-known open-source Spring Boot projects (Spring PetClinic, JHipster,
Apache DolphinScheduler, Apache ShenYu, ThingsBoard, JeecgBoot, RuoYi, mall and more: 282 modules) and
reviewed every finding by hand.

- **45 of 45** HikariCP services we could evaluate wait **up to 30 s** for a connection when the pool runs
  dry, and **none** detects connection leaks. On Spring Boot's defaults (200 threads, 10 connections), a
  database that slows from 30 ms to 3 s per query blocks all 200 request threads in about **4 seconds**,
  health checks included.
- The Spring PetClinic microservices gateway calls other services with **no HTTP timeout at all**:

  ```java
  @Bean @LoadBalanced
  RestTemplate loadBalancedRestTemplate() {
      return new RestTemplate();   // connect and read timeout: infinite
  }
  ```
  ```
  WARNING ST-HTTP-001  spring-petclinic-api-gateway/.../ApiGatewayApplication.java:55:16
          new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite).
          Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
  ```
- AI features in JeecgBoot run on a 10-thread pool with an **unbounded queue**, so slow LLM calls pile up in
  memory instead of being rejected.
- **1 false positive in 199 findings (0.5%).** When the config isn't in the repo (Config Server, Nacos), or
  another pool owns the DataSource, it says *not evaluated, and why*. It never guesses.

**[Read the full write-up](docs/case-study/what-we-found-in-20-spring-boot-projects.md)**: the code, why each
pattern causes outages, the fixes, and what it deliberately doesn't flag. Method, per-rule precision and raw
results: [corpus report](docs/corpus/2026-11-corpus-report.md).

**Try it on your own repo now.** It's static only, so nothing is built and nothing leaves your machine:

```bash
curl -fsSLO https://github.com/pvgiang/spring-tune/releases/download/v0.1.0/springtune-cli-0.1.0.jar
java -jar springtune-cli-0.1.0.jar check . --profile prod
```

Found something, or a false positive? [Open an issue](https://github.com/pvgiang/spring-tune/issues): every
false positive becomes a regression test.

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
and runs the GitHub Action against the sample. Releases: push a `vX.Y.Z` tag, or run *Actions → Release →
Run workflow* with the version. `.github/workflows/release.yml` builds, tests and publishes the GitHub Release
with notes from `docs/releases/`.

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
- [Case study: what we found in 20 popular Spring Boot projects](docs/case-study/what-we-found-in-20-spring-boot-projects.md)
- [Corpus run: method, per-rule precision, raw results](docs/corpus/2026-11-corpus-report.md)
- [Runtime starter](docs/starter.md)
- [CI integration (GitHub code scanning)](docs/ci-integration.md)
- [Release notes](docs/releases/)
- [Business & implementation plan](docs/business-and-implementation-plan.md)
