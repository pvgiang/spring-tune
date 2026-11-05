# spring-tune

Spring Boot config safety and self-tuning.

Catch the Spring Boot configuration that will take you down before production does. Then let it tune itself when you're ready.

SpringTune has two parts that share one model and one set of rule IDs, plus an opt-in limiter:

| Part | When it runs | What it does |
|---|---|---|
| **`springtune check`** (CLI, Maven plugin, Gradle plugin, GitHub Action) | CI, before deploy | Static analysis of `application*.yml/properties` (resolved exactly like Spring Boot does), Java sources and Helm/Kubernetes manifests. 13 rules for pool exhaustion, hung threads and retry storms. Text/Markdown/JSON/SARIF output; fails the build at a threshold. |
| **`springtune-spring-boot-starter`** | Runtime | `/actuator/tuning` with live knobs (HikariCP, `ThreadPoolTaskExecutor`), signals and **explained, budget-capped recommendations**. Read-only by default; experimental guarded `apply` mode (one reviewed change, verified and auto-reverted) is opt-in. |
| **`springtune-limiter`** (opt-in) | Runtime | Adaptive concurrency limiter (servlet and WebFlux): fast `503` + `Retry-After` instead of a queue when a downstream saturates. Off by default; shadow mode (count, never reject) until you enforce. [Docs](docs/limiter.md) |

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
curl -fsSLO https://github.com/pvgiang/spring-tune/releases/download/v0.3.0/springtune-cli-0.3.0.jar
java -jar springtune-cli-0.3.0.jar check path/to/repo --profile prod
# exit 0 = clean, 1 = findings at/above --fail-on (default ERROR), 2 = usage/config error
```

**Maven plugin and runtime starter** (`io.github.winkytek.springtune:*:0.3.0`, Maven Central from 0.3.0;
0.1.0 was published as `io.github.winky.springtune` in a GitHub release zip only):

```xml
<plugin>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.3.0</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

```xml
<dependency>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

```yaml
management.endpoints.web.exposure.include: health,tuning
springtune.budget:            # optional, caps pool recommendations: floor(100 x 0.8 / 6) = 13
  database-max-connections: 100
  max-replicas: 6
```

**Adaptive limiter** (from 0.3.0, opt-in, separate artifact `springtune-limiter`). Starts in shadow mode:
it only counts the requests it would reject. Actuator, SSE and WebSocket requests are never limited. In the
module's load test (160 callers, downstream capacity 10) it cut server-side p99 of accepted requests from
351–435 ms to 70–108 ms; read [docs/limiter.md](docs/limiter.md) (when it hurts, retry budgets, rollout) before
enforcing.

```yaml
springtune.limiter.enabled: true     # shadow; add springtune.limiter.enforce: true after the rollout steps
```

**Gradle plugin** (from 0.3.0; Gradle 8.5+, configuration cache compatible). The resolved runtime classpath
decides which rules apply, so dependencies declared through version catalogs and convention plugins count:

```kotlin
plugins {
    id("io.github.winkytek.springtune") version "0.3.0"   // ./gradlew check now runs springtuneCheck
}
springtune { profiles.set(listOf("prod")) }            // optional; see docs/check.md#gradle-plugin
```

Until it is on the Gradle Plugin Portal, add `mavenCentral()` to `pluginManagement.repositories`
([setup](docs/check.md#gradle-plugin)).

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
curl -fsSLO https://github.com/pvgiang/spring-tune/releases/download/v0.3.0/springtune-cli-0.3.0.jar
java -jar springtune-cli-0.3.0.jar check . --profile prod
```

Found something, or a false positive? [Open an issue](https://github.com/pvgiang/spring-tune/issues/new/choose)
(forms for false positives, rule requests, recommendation feedback, fleet/approval needs and bugs): every false
positive becomes a regression test.

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

Requires JDK 17+ and Maven 3.9+. The Gradle plugin's functional tests run real Gradle builds: they use
`-Dspringtune.it.gradle=<gradle or GRADLE_HOME>`, else `gradle` on `PATH`, and are skipped without one. CI
(`.github/workflows/ci.yml`) runs JDK 17/21/25, Boot 4.1 and 3.5, Gradle 8.14 and 9.8, and runs the GitHub
Action against the sample. Releases: push a `vX.Y.Z` tag, or run *Actions → Release →
Run workflow* with the version. `.github/workflows/release.yml` builds, tests and publishes the GitHub Release
with notes from `docs/releases/`, and deploys to Maven Central once the secrets exist ([releasing](docs/releasing.md)).

## Modules

| Module | Purpose |
|---|---|
| `springtune-model` | Shared domain: `Finding`, `RuleDescriptor`, `Knob`, `Recommendation`, `ConnectionBudget` |
| `springtune-check-engine` | Config resolution, scanners, rule API, 13 rules, reporters |
| `springtune-check-cli` | `springtune check` / `springtune rules` (runnable `-all.jar`) |
| `springtune-maven-plugin` | `mvn springtune:check`, bound to `verify` |
| `springtune-gradle-plugin` (+ `-marker`, `-tests`) | Plugin `io.github.winkytek.springtune`: `./gradlew springtuneCheck`, wired into `check`; functional tests on real Gradle builds |
| `springtune-spring-boot-autoconfigure` / `-starter` | Runtime registry, sampler, recommendations, `/actuator/tuning` |
| `springtune-limiter` | Opt-in adaptive concurrency limiter (`Filter`/`WebFilter` over Netflix concurrency-limits), Micrometer metrics |
| `springtune-samples/broken-config` | Runnable demo and end-to-end fixture |
| `action.yml` | GitHub Action wrapping the CLI |

## Docs

- [Delivery brief: scope, decisions, acceptance criteria](docs/delivery/iteration-1-brief.md)
- [Architecture](docs/architecture.md)
- [Delivery brief, iteration 2: Gradle plugin](docs/delivery/iteration-2-brief.md)
- [The check: CLI, Maven plugin, Gradle plugin, how config is resolved, limitations](docs/check.md)
- [`springtune.yml` reference](docs/configuration.md)
- [Rules](docs/rules/README.md)
- [Case study: what we found in 20 popular Spring Boot projects](docs/case-study/what-we-found-in-20-spring-boot-projects.md)
- [Corpus run: method, per-rule precision, raw results](docs/corpus/2026-11-corpus-report.md)
- [Runtime starter](docs/starter.md)
- [Adaptive limiter: when it helps, when it hurts, rollout, measurements](docs/limiter.md)
- [CI integration (GitHub code scanning)](docs/ci-integration.md)
- [Grafana dashboard for the starter, apply and limiter metrics](docs/grafana/README.md)
- [Adoption gates: what we measure and how (no telemetry)](docs/adoption/gates.md)
- [Releasing: Maven Central, Gradle Plugin Portal, one-time setup](docs/releasing.md)
- [Release notes](docs/releases/)
- [Roadmap after 0.3.0](docs/roadmap.md)
- [Business & implementation plan](docs/business-and-implementation-plan.md)

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attributions. Every published jar carries both files in
`META-INF/`.
