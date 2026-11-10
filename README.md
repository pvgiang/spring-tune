# spring-tune

A CI check for risky Spring Boot configuration, plus read-only runtime advice.

Catch the Spring Boot configuration that will take you down before production does: pool exhaustion, hung threads
and retry storms, reported on the exact line in your pull request. At runtime, the starter explains what it would
change and why, without changing anything; applying a change is an experimental, opt-in mode (5 of its 6
[pre-registered bars](docs/delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code)
are met; the missing one is a team running it in production).

SpringTune has two parts that share one model and one set of rule IDs, plus an opt-in limiter:

| Part | When it runs | What it does |
|---|---|---|
| **`springtune check`** (CLI, Maven plugin, Gradle plugin, GitHub Action) | CI, before deploy | Static analysis of `application*.yml/properties` (resolved exactly like Spring Boot does), Java sources and Helm/Kubernetes manifests. 13 rules for pool exhaustion, hung threads and retry storms. Text/Markdown/JSON/SARIF output; fails the build at a threshold. |
| **`springtune-spring-boot-starter`** | Runtime | `/actuator/tuning` with live knobs (HikariCP, `ThreadPoolTaskExecutor`), signals and **explained, budget-capped recommendations**. Read-only by default; experimental guarded `apply` mode (one reviewed change, verified and auto-reverted) is opt-in. |
| **`springtune-limiter`** (opt-in) | Runtime | Adaptive concurrency limiter (servlet and WebFlux): fast `503` + `Retry-After` instead of a queue when a downstream saturates. Off by default; shadow mode (count, never reject) until you enforce. [Docs](docs/limiter.md) |

## What it looks like

Every output below is real and unedited except where marked *trimmed*. It was produced by
[`scripts/readme/capture-check-runs.sh`](scripts/readme/capture-check-runs.sh) and
[`capture-runtime-runs.sh`](scripts/readme/capture-runtime-runs.sh). The raw outputs are in
[`docs/readme-runs/`](docs/readme-runs/).

- **Check:** the released 0.4.1 CLI (`springtune-cli-0.4.1.jar`, verified against the v0.4.1 release's
  `SHA256SUMS`), run on copies of [examples/broken-config-ci](examples/broken-config-ci).
- **Runtime:** the [sample app](springtune-samples/broken-config) on Spring Boot 3.5.16, under load from `curl`.
  - It runs the starter from this branch, 0.4.2-SNAPSHOT. `evaluations` and `/actuator/tuning/report` arrive in the
    release after 0.4.1; the recommendation rules are unchanged since 0.4.0.
  - The saturation window is shortened from 5 minutes to 1 minute (`springtune.hikari.window=1m`) so each case takes
    a minute. All other settings are defaults.

### In CI: the build fails on the exact line, with the fix

The demo service as shipped. Its `springtune.yml` sets `failOn: WARNING`.

```console
$ java -jar springtune-cli-0.4.1.jar check .
SpringTune 0.4.1: 1 module(s), 5 finding(s) (0 error, 4 warning, 1 info), 0 suppressed

WARNING ST-HTTP-001  src/main/java/com/example/orders/InventoryClient.java:10:39
        new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite).
        Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HTTP-001.md

WARNING ST-HIKARI-002  src/main/resources/application.yml:1
        leakDetectionThreshold is not set (HikariCP default 0 = disabled).
        Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-002.md

INFO    ST-TX-001  src/main/resources/application.yml:1
        No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run.
        Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TX-001.md

WARNING ST-HIKARI-001  src/main/resources/application.yml:9:27
        connectionTimeout is 30000 ms, above the 5000 ms threshold.
        Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-001.md

WARNING ST-TOMCAT-001  src/main/resources/application.yml:15:12
        Tomcat may run 400 request threads against 10 pooled connections, a ratio of 40:1 (threshold 10:1).
        Fix: Bound concurrency to what the database can serve: lower server.tomcat.threads.max, or raise the pool within the connection budget (ST-HIKARI-004). Keep connectionTimeout short (ST-HIKARI-001).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TOMCAT-001.md

Not evaluated (2):
  - [.] ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
  - [.] ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml

FAILED: 4 finding(s) at or above WARNING. Fix them or suppress with a reason in springtune.yml.
$ echo $?
1
```

The four other cases a team meets in its first week:

| Case | What changes | Result | Exit |
|---|---|---|---|
| After the four fixes | the one-line fixes from the [demo README](examples/broken-config-ci#what-fails-and-how-to-fix-it) | 0 warnings; the INFO about timeouts stays | 0 |
| Accept a finding instead | a suppression **with a required reason** in `springtune.yml` | the reason is printed; the other 3 warnings still fail | 1 |
| Config lives in Config Server | `spring.config.import: configserver:...` | **not evaluated, and why**: it never guesses | 0 |
| Declare the database limit | `databases[].maxConnections: 100`, `maxReplicas: 6`, pool 20 | ERROR: 20 × 6 = 120 connections > 80 usable | 1 |

<details><summary><b>After the four fixes</b> (exit 0)</summary>

```console
$ java -jar springtune-cli-0.4.1.jar check .
SpringTune 0.4.1: 1 module(s), 1 finding(s) (0 error, 0 warning, 1 info), 0 suppressed

INFO    ST-TX-001  src/main/resources/application.yml:1
        No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run.
        Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TX-001.md

Not evaluated (2):
  - [.] ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
  - [.] ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml
$ echo $?
0
```
</details>

<details><summary><b>Accept a finding instead</b> (suppressed, with its reason)</summary>

```yaml
# springtune.yml
suppress:
  - rule: ST-TOMCAT-001
    reason: "Most endpoints never touch the database; reviewed 2026-11"   # required
```

```console
$ java -jar springtune-cli-0.4.1.jar check .
SpringTune 0.4.1: 1 module(s), 4 finding(s) (0 error, 3 warning, 1 info), 1 suppressed

WARNING ST-HTTP-001  src/main/java/com/example/orders/InventoryClient.java:10:39
        new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite).
        Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HTTP-001.md

WARNING ST-HIKARI-002  src/main/resources/application.yml:1
        leakDetectionThreshold is not set (HikariCP default 0 = disabled).
        Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-002.md

INFO    ST-TX-001  src/main/resources/application.yml:1
        No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run.
        Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TX-001.md

WARNING ST-HIKARI-001  src/main/resources/application.yml:9:27
        connectionTimeout is 30000 ms, above the 5000 ms threshold.
        Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-001.md

Suppressed (1):
  - ST-TOMCAT-001 src/main/resources/application.yml:15:12: Most endpoints never touch the database; reviewed 2026-11

Not evaluated (2):
  - [.] ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
  - [.] ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml

FAILED: 3 finding(s) at or above WARNING. Fix them or suppress with a reason in springtune.yml.
$ echo $?
1
```
</details>

<details><summary><b>Config lives in Config Server</b> (not evaluated, and why)</summary>

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: payments-service
  config:
    import: "configserver:http://config:8888"
```

```console
$ java -jar springtune-cli-0.4.1.jar check .
SpringTune 0.4.1: 1 module(s), 0 finding(s) (0 error, 0 warning, 0 info), 0 suppressed

Not evaluated (1):
  - [.] Local config is incomplete: spring.config.import loads remote configuration (configserver:http://config:8888); rules that report missing properties were not evaluated for this module
$ echo $?
0
```
</details>

<details><summary><b>Declare the database limit</b> (the connection budget: ERROR)</summary>

```yaml
# springtune.yml (added), with spring.datasource.hikari.maximum-pool-size: 20
databases:
  - name: orders-db
    maxConnections: 100
services:
  - path: .
    maxReplicas: 6
```

```console
$ java -jar springtune-cli-0.4.1.jar check . --fail-on ERROR
SpringTune 0.4.1: 1 module(s), 6 finding(s) (1 error, 4 warning, 1 info), 0 suppressed

WARNING ST-HTTP-001  src/main/java/com/example/orders/InventoryClient.java:10:39
        new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite).
        Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HTTP-001.md

WARNING ST-HIKARI-002  src/main/resources/application.yml:1
        leakDetectionThreshold is not set (HikariCP default 0 = disabled).
        Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-002.md

INFO    ST-TX-001  src/main/resources/application.yml:1
        No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run.
        Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TX-001.md

ERROR   ST-HIKARI-004  src/main/resources/application.yml:8:26
        Database 'orders-db' allows 80 pool connections (max_connections 100 x safety factor 0.8) but its services can open 120: . 20x6 [replicas from springtune.yml services[path=.]].
        Fix: Lower maximum-pool-size (proportional share for this service: 13), cap maxReplicas, or raise max_connections / add a connection proxy such as PgBouncer.
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-004.md

WARNING ST-HIKARI-001  src/main/resources/application.yml:9:27
        connectionTimeout is 30000 ms, above the 5000 ms threshold.
        Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-HIKARI-001.md

WARNING ST-TOMCAT-001  src/main/resources/application.yml:15:12
        Tomcat may run 400 request threads against 20 pooled connections, a ratio of 20:1 (threshold 10:1).
        Fix: Bound concurrency to what the database can serve: lower server.tomcat.threads.max, or raise the pool within the connection budget (ST-HIKARI-004). Keep connectionTimeout short (ST-HIKARI-001).
        Docs: https://github.com/pvgiang/spring-tune/blob/main/docs/rules/ST-TOMCAT-001.md

Not evaluated (1):
  - [.] ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml

FAILED: 1 finding(s) at or above ERROR. Fix them or suppress with a reason in springtune.yml.
$ echo $?
1
```
</details>

### At runtime: advice with its reason, nothing changed

Add the starter and expose `tuning`, then read `GET /actuator/tuning`. The sample's budget is 100 connections × 0.8
across 6 replicas, which caps the pool at **13**. The same kind of load gives a different answer depending on what
the signals say:

| Case | Pool | Load (callers × connection hold time) | `GET /actuator/tuning` says |
|---|---|---|---|
| A: just started | 20 | none | no recommendation; each rule `COLLECTING`, 10 of 58 s seen |
| B: pool is the bottleneck, over budget | 20 | 60 × 50 ms | `ST-REC-HIKARI-001` **HOLD**: do not raise, already above the budget cap of 13 |
| C: slow queries | 20 | 60 × 2 s | `ST-REC-HIKARI-002` **HOLD**: do not raise, the database work is the bottleneck |
| D: pool is the bottleneck, within budget | 8 | 40 × 50 ms | `ST-REC-HIKARI-001` **INCREASE 8 → 10**, within the cap of 13 |

Nothing is applied in any case: the default mode is `advise`.

<details><summary><b>A: just started</b> (trimmed: the pool's evaluations)</summary>

```json
{
  "recommendations": [],
  "evaluations": [
    {
      "component": "hikari:orders-pool",
      "ruleId": "ST-REC-HIKARI-001",
      "status": "COLLECTING",
      "seenSamples": 6,
      "minSamples": 3,
      "seenSeconds": 10,
      "neededSeconds": 58,
      "missingMeters": null,
      "supersededBy": null
    },
    {
      "ruleId": "ST-REC-HIKARI-002",
      "status": "COLLECTING"
    },
    {
      "ruleId": "ST-REC-HIKARI-004",
      "status": "COLLECTING"
    },
    {
      "ruleId": "ST-REC-HIKARI-003",
      "status": "COLLECTING"
    }
  ]
}
```
</details>

<details><summary><b>B: threads wait, connections come back in 50 ms, pool already over budget</b> (trimmed)</summary>

```json
{
  "recommendations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "action": "HOLD",
      "currentValue": 20,
      "recommendedValue": 20,
      "reason": "Do not raise the pool. It is the bottleneck (threads wait while connections are returned in 50 ms), but it is already above its connection budget cap of 13 (current 20), so the fleet can exceed the database's max_connections at peak scale (ST-HIKARI-004). Reduce per-request connection use, cap replicas, or raise the database's max_connections.",
      "evidence": {
        "window": "1m",
        "samples": "30",
        "samplesWithWaitingThreads": "100%",
        "maxWaitingThreads": "39",
        "meanConnectionHoldTime": "50 ms",
        "meanAcquireTime": "92 ms",
        "slowHoldThreshold": "500 ms",
        "budgetCap": "13"
      },
      "confidence": "MEDIUM"
    }
  ],
  "evaluations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "status": "RECOMMENDING"
    },
    {
      "ruleId": "ST-REC-HIKARI-002",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-004",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-003",
      "status": "COLLECTING"
    }
  ]
}
```
</details>

<details><summary><b>C: threads wait because each connection is held too long</b> (trimmed)</summary>

```json
{
  "recommendations": [
    {
      "ruleId": "ST-REC-HIKARI-002",
      "action": "HOLD",
      "currentValue": 20,
      "recommendedValue": 20,
      "reason": "Do not raise the pool. Threads wait for connections because each connection is held for 2000 ms on average (threshold 500 ms). The database work is the bottleneck, and more connections would add load to an already busy database. Look for slow queries, long transactions or remote calls made inside transactions.",
      "evidence": {
        "window": "1m",
        "samples": "30",
        "samplesWithWaitingThreads": "100%",
        "maxWaitingThreads": "40",
        "meanConnectionHoldTime": "2000 ms",
        "meanAcquireTime": "4013 ms",
        "slowHoldThreshold": "500 ms"
      },
      "confidence": "HIGH"
    }
  ],
  "evaluations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-002",
      "status": "RECOMMENDING"
    },
    {
      "ruleId": "ST-REC-HIKARI-004",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-003",
      "status": "COLLECTING"
    }
  ]
}
```
</details>

<details><summary><b>D: the same short holds on a pool of 8</b> (trimmed), and the shareable report</summary>

```json
{
  "recommendations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "action": "INCREASE",
      "currentValue": 8,
      "recommendedValue": 10,
      "reason": "Raise maximumPoolSize from 8 to 10. Threads waited for a connection in 100% of samples over 1m, while connections were returned quickly (50 ms average hold), so the pool size is the bottleneck. This stays within the connection budget cap of 13.",
      "evidence": {
        "window": "1m",
        "samples": "30",
        "samplesWithWaitingThreads": "100%",
        "maxWaitingThreads": "32",
        "meanConnectionHoldTime": "50 ms",
        "meanAcquireTime": "194 ms",
        "slowHoldThreshold": "500 ms",
        "budgetCap": "13"
      },
      "confidence": "HIGH"
    }
  ],
  "evaluations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "status": "RECOMMENDING"
    },
    {
      "ruleId": "ST-REC-HIKARI-002",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-004",
      "status": "QUIET"
    },
    {
      "ruleId": "ST-REC-HIKARI-003",
      "status": "COLLECTING"
    }
  ]
}
```

`GET /actuator/tuning/report` turns the same state into something you can paste into a
[recommendation feedback](https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml)
issue. It is built only from SpringTune's own rule IDs, numbers, enum values and pseudonyms such as `hikari#1`, and
each recommendation has a `feedbackUrl` that pre-fills the form. Trimmed below: `components` dropped,
`feedbackUrl` shortened.

```json
{
  "format": "springtune-report/1",
  "springtuneVersion": "0.4.2-SNAPSHOT",
  "springBootVersion": "3.5.16",
  "mode": "ADVISE",
  "recommendations": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "knob": "hikari#1.maximumPoolSize",
      "action": "INCREASE",
      "currentValue": 8,
      "recommendedValue": 10,
      "confidence": "HIGH",
      "evidence": {
        "window": "1m",
        "samples": "30",
        "samplesWithWaitingThreads": "100%",
        "maxWaitingThreads": "32",
        "meanConnectionHoldTime": "50 ms",
        "meanAcquireTime": "194 ms",
        "slowHoldThreshold": "500 ms",
        "budgetCap": "13"
      },
      "createdAt": "2026-11-10T14:03:11.788116935Z",
      "feedbackUrl": "https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml&title=%5Brecommendation%5D%20ST…"
    }
  ],
  "omitted": ["componentIds", "knobIds", "knobSources", "signals", "recommendation.reason", "recommendation.relatedRuleIds", "recommendation.evidence.nonNumeric", "applyPlans", "apply.frozenReason", "apply.requiredRole", "apply.guardrails", "apply.knobs", "audit.who", "audit.why", "audit.reasons", "audit.evidence", "audit.capacity"]
}
```
</details>

## Quick start

Everything below is SpringTune **0.4.1**, groupId `io.github.winkytek.springtune` (no rule changes from 0.4.0, so
check results are identical; [release notes](docs/releases/v0.4.1.md)). If Maven Central still answers 404 for
0.4.1, its publication is pending: use 0.4.0, which is on Central with the same rules, until it appears.
To see it fail on a real project first, open the demo: [examples/broken-config-ci](examples/broken-config-ci).

**GitHub Action** (findings show up as code-scanning alerts on the exact line). It downloads the CLI jar from the
GitHub release and runs it only if its SHA-256 matches the release's `SHA256SUMS`
([details](docs/ci-integration.md#github-actions-with-code-scanning-sarif)):

<!-- snippet:action -->
```yaml
# .github/workflows/springtune.yml
on: [pull_request]
permissions: { contents: read, security-events: write }
jobs:
  springtune:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pvgiang/spring-tune@v0.4.1
        id: springtune
        with:
          version: "0.4.1"       # pass it: the v0.4.1 tag defaults to CLI 0.4.0 (docs/releases/v0.4.1.md)
          profile: prod          # optional
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: ${{ steps.springtune.outputs.sarif }}
```

The same workflow with comments: [examples/github-workflow.yml](examples/github-workflow.yml).

**CLI** (any build tool, Java 17+), from Maven Central (`io.github.winkytek.springtune:springtune-check-cli:0.4.1:all`),
checked against Central's SHA-256:

<!-- snippet:cli -->
```bash
base=https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1
curl -fsSLO $base/springtune-check-cli-0.4.1-all.jar
echo "$(curl -fsSL $base/springtune-check-cli-0.4.1-all.jar.sha256)  springtune-check-cli-0.4.1-all.jar" \
  | sha256sum -c -                                   # macOS: shasum -a 256 -c -
java -jar springtune-check-cli-0.4.1-all.jar check path/to/repo --profile prod
# exit 0 = clean, 1 = findings at/above --fail-on (default ERROR), 2 = usage/config error
```

The CLI is also attached to the [v0.4.1 GitHub release](https://github.com/pvgiang/spring-tune/releases/tag/v0.4.1)
as `springtune-cli-0.4.1.jar`, listed in the release's `SHA256SUMS` (the jar the Action runs).

**Maven plugin and runtime starter** (Maven Central from 0.4.0, no extra repository; 0.3.0 was a GitHub release
only, and 0.1.0 used an older groupId, see the [0.3.0 notes](docs/releases/v0.3.0.md)):

<!-- snippet:maven-plugin -->
```xml
<plugin>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.4.1</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

<!-- snippet:maven-starter -->
```xml
<dependency>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.4.1</version>
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

**Gradle plugin** (Gradle 8.5+, configuration cache compatible). The resolved runtime classpath decides which rules
apply, so dependencies declared through version catalogs and convention plugins count:

<!-- snippet:gradle-plugin -->
```kotlin
plugins {
    id("io.github.winkytek.springtune") version "0.4.1"   // ./gradlew check now runs springtuneCheck
}
springtune { profiles.set(listOf("prod")) }            // optional; see docs/check.md#gradle-plugin
```

This works with Gradle's default plugin repository: the plugin is not listed on the Gradle Plugin Portal yet, but
the Portal serves it from Maven Central. Until the Plugin Portal listing, add `mavenCentral()` to
`pluginManagement.repositories` in `settings.gradle.kts` if your build doesn't use `gradlePluginPortal()` (for
example, a corporate mirror only). The project itself needs `mavenCentral()` (or a mirror of it) in `repositories`,
where the plugin resolves the check engine ([setup](docs/check.md#gradle-plugin)).

## Case study: what we found in 20 popular Spring Boot projects

We ran `springtune check` over 20 well-known open-source Spring Boot projects (Spring PetClinic, JHipster,
Apache DolphinScheduler, Apache ShenYu, ThingsBoard, JeecgBoot, RuoYi, mall and more: 282 modules) and
reviewed every finding by hand.

- **All 45** HikariCP modules we could evaluate wait longer than 5 s for a connection when the pool runs
  dry (44 up to **30 s**: 38 on the unset default, 6 with `30000` written out; 1 waits 10 s), and **none**
  detects connection leaks ([0.4.0 re-run](docs/corpus/2026-11-09-0.4.0-rerun.md)). On Spring Boot's defaults (200 threads, 10 connections), a
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
curl -fsSLO https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1-all.jar
java -jar springtune-check-cli-0.4.1-all.jar check . --profile prod
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
| `action.yml` | GitHub Action wrapping the CLI (verifies the downloaded jar against the release's `SHA256SUMS`) |
| `examples/broken-config-ci` | Standalone demo on 0.4.1 from Central: the check fails on four findings, with the fix for each |

## Docs

- [Delivery brief: scope, decisions, acceptance criteria](docs/delivery/iteration-1-brief.md)
- [Architecture](docs/architecture.md)
- [Delivery brief, iteration 2: Gradle plugin](docs/delivery/iteration-2-brief.md)
- [The check: CLI, Maven plugin, Gradle plugin, how config is resolved, limitations](docs/check.md)
- [`springtune.yml` reference](docs/configuration.md)
- [Rules](docs/rules/README.md)
- [Case study: what we found in 20 popular Spring Boot projects](docs/case-study/what-we-found-in-20-spring-boot-projects.md)
- [Corpus run: method, per-rule precision, raw results](docs/corpus/2026-11-corpus-report.md)
- Rule deep dives: [connection acquire timeout (ST-HIKARI-001)](docs/deep-dives/ST-HIKARI-001-connection-timeout.md),
  [HTTP clients without timeouts (ST-HTTP-001)](docs/deep-dives/ST-HTTP-001-http-client-timeouts.md),
  [transactions and queries without a timeout (ST-TX-001)](docs/deep-dives/ST-TX-001-transaction-query-timeouts.md)
- [Runtime starter](docs/starter.md)
- [Try the starter in staging for two weeks (design partners)](docs/design-partners.md): what you will see and when, and how to report each recommendation
- [PetClinic benchmark: what the starter recommends under load (pre-registered; 2 of 4 matched as registered, 1 more only with a poll bound added afterwards, 1 miss)](docs/benchmarks/petclinic.md)
- [Load tests: apply mode against a real PostgreSQL](docs/loadtests.md)
- [Adaptive limiter: when it helps, when it hurts, rollout, measurements](docs/limiter.md)
- [CI integration (GitHub code scanning)](docs/ci-integration.md)
- [Grafana dashboard for the starter, apply and limiter metrics](docs/grafana/README.md)
- [Adoption gates: what we measure and how (no telemetry)](docs/adoption/gates.md)
- [Releasing: Maven Central, Gradle Plugin Portal, one-time setup](docs/releasing.md)
- [Release notes](docs/releases/)
- [Roadmap (current release: 0.4.1)](docs/roadmap.md)
- [Business & implementation plan](docs/business-and-implementation-plan.md)
- [Contributing](CONTRIBUTING.md) (build, tests, adding a rule, DCO sign-off) · [Security policy](SECURITY.md) · [Code of conduct](CODE_OF_CONDUCT.md)

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attributions. Every published jar carries both files in
`META-INF/`.
