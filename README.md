# spring-tune

**Catch the Spring Boot configuration that will take you down before production does.**

SpringTune finds the settings behind pool exhaustion, hung threads and retry storms: a 30-second wait for a database
connection, an HTTP client with no timeout, 400 request threads sharing 10 connections. It reports each one on the
exact line, with the reason and the fix. It comes in two independent parts; use either or both:

- **The check** reads your `application*.yml/properties`, Java sources and Helm/Kubernetes values in CI and fails
  the build at the level you choose. It only reads files: nothing is built or executed, nothing leaves your machine.
- **The runtime starter** watches HikariCP pools and `ThreadPoolTaskExecutor`s in a running app and explains what it
  would change and why, at `GET /actuator/tuning`. It changes nothing: applying a change is a separate,
  experimental, opt-in mode.

There is no telemetry.

## Start here: pick one

| You want to | Use | You need | Go to |
|---|---|---|---|
| Try it on any repository right now, without touching the build | CLI | Java 17+ | [1. CLI](#1-cli) |
| Fail the Maven build on risky configuration | Maven plugin | Maven, Java 17+ | [2. Maven plugin](#2-maven-plugin) |
| Fail the Gradle build on risky configuration | Gradle plugin | Gradle 8.5+, Java 17+ | [3. Gradle plugin](#3-gradle-plugin) |
| See findings as annotations on the pull request lines | GitHub Action | GitHub Actions | [4. GitHub Action](#4-github-action) |
| Get explained pool and executor advice from a running app | Spring Boot starter | Spring Boot 3.5 or 4.1 (tested) | [5. Spring Boot starter](#5-spring-boot-starter) |

All snippets use **SpringTune 0.4.1**, groupId `io.github.winkytek.springtune`
([release notes](docs/releases/v0.4.1.md)). The CLI, Maven plugin and Gradle plugin run the same 13
[rules](docs/rules/README.md) and give the same findings.

Every output in this README is real and was captured with 0.4.1 on
[`examples/broken-config-ci`](examples/broken-config-ci), a small service with four deliberate mistakes. Long
outputs are marked *trimmed*. The raw captures and the scripts that produce them are in
[`docs/readme-runs/`](docs/readme-runs/) and [`scripts/readme/`](scripts/readme/).

---

## 1. CLI

One jar, any build tool, any repository.

**Step 1: download and verify, then run.** Replace `path/to/repo` with your project:

<!-- snippet:cli -->
```bash
base=https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1
curl -fsSLO $base/springtune-check-cli-0.4.1-all.jar
echo "$(curl -fsSL $base/springtune-check-cli-0.4.1-all.jar.sha256)  springtune-check-cli-0.4.1-all.jar" \
  | sha256sum -c -                                   # macOS: shasum -a 256 -c -
java -jar springtune-check-cli-0.4.1-all.jar check path/to/repo --profile prod
# exit 0 = clean, 1 = findings at/above --fail-on (default ERROR), 2 = usage/config error
```

The same CLI is attached to the [v0.4.1 GitHub release](https://github.com/pvgiang/spring-tune/releases/tag/v0.4.1)
as `springtune-cli-0.4.1.jar`, with its SHA-256 in the release's `SHA256SUMS`. The outputs below were captured with
that jar.

**Step 2: read the result.** Each finding gives the rule, the file and line, what is wrong, the fix, and a link to
the rule's page. On the demo service:

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

The demo's `springtune.yml` sets `failOn: WARNING`, so the four warnings fail the run (exit 1). Without a
`springtune.yml`, only ERROR findings fail it.

| Exit code | Meaning |
|---|---|
| `0` | No finding at or above the fail level (lower findings are still printed) |
| `1` | At least one finding at or above the fail level |
| `2` | Usage or configuration error |

**Step 3: options you will use.**

| Option | What it does |
|---|---|
| `--profile prod` | Resolve configuration with these Spring profiles active, as Spring Boot would |
| `--fail-on WARNING` | Fail at `ERROR` (default), `WARNING` or `INFO`; `NONE` never fails |
| `--format text,markdown,json,sarif` | Report files to write (default `text`); `-o DIR` sets their directory (default `springtune-reports`) |
| `--module DIR` | Check only this module (repeatable); by default every Spring Boot module under the root |
| `--config FILE` | A `springtune.yml` elsewhere than the project root |

`java -jar springtune-check-cli-0.4.1-all.jar rules` lists the 13 rules.

**Step 4: tune it with `springtune.yml`** at the project root (optional; [reference](docs/configuration.md)). The
same file drives the Maven and Gradle plugins:

```yaml
failOn: WARNING                 # fail level; the default is ERROR
suppress:                       # accept a finding instead of fixing it; the reason is required and printed
  - rule: ST-TOMCAT-001
    reason: "Most endpoints never touch the database; reviewed 2026-11"
databases:                      # enables the connection-budget rule (ST-HIKARI-004)
  - name: orders-db
    maxConnections: 100
services:
  - path: .
    maxReplicas: 6
```

What each of those changes looks like, on the same demo:

| Case | What changes | Result | Exit |
|---|---|---|---|
| After the fixes | the four one-line fixes from the [demo README](examples/broken-config-ci#what-fails-and-how-to-fix-it) | no warnings; the INFO about timeouts stays | 0 |
| A finding accepted | a `suppress` entry with its reason | the reason is printed; the other 3 warnings still fail | 1 |
| Config in Config Server | `spring.config.import: configserver:...` | *not evaluated, and why*: it never guesses | 0 |
| Database limit declared | `databases` and `services` as above, pool size 20 | ERROR: 20 × 6 replicas = 120 connections > 80 usable | 1 |

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

<details><summary><b>A finding accepted</b> (suppressed, with its reason)</summary>

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

<details><summary><b>Config in Config Server</b> (not evaluated, and why)</summary>

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

<details><summary><b>Database limit declared</b> (the connection budget)</summary>

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

---

## 2. Maven plugin

**Step 1: add the plugin** inside `<build><plugins>` of your `pom.xml`. It comes from Maven Central; no extra
repository is needed:

<!-- snippet:maven-plugin -->
```xml
<plugin>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.4.1</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

**Step 2: run your normal build.** The check is bound to `verify`:

```bash
mvn verify
```

**Step 3: read the result.** On the demo service (*trimmed*: Maven's own build lines and the `[ERROR]` help lines
removed, the project path shortened to `<project>`):

```console
$ mvn -B verify
[INFO] --- springtune:0.4.1:check (default) @ orders-service ---
[INFO] SpringTune 0.4.1: 5 finding(s), 0 suppressed
[WARNING] ST-HTTP-001 src/main/java/com/example/orders/InventoryClient.java:10:39: new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite). Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
[WARNING] ST-HIKARI-002 src/main/resources/application.yml:1: leakDetectionThreshold is not set (HikariCP default 0 = disabled). Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.
[INFO] ST-TX-001 src/main/resources/application.yml:1: No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run. Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
[WARNING] ST-HIKARI-001 src/main/resources/application.yml:9:27: connectionTimeout is 30000 ms, above the 5000 ms threshold. Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).
[WARNING] ST-TOMCAT-001 src/main/resources/application.yml:15:12: Tomcat may run 400 request threads against 10 pooled connections, a ratio of 40:1 (threshold 10:1). Fix: Bound concurrency to what the database can serve: lower server.tomcat.threads.max, or raise the pool within the connection budget (ST-HIKARI-004). Keep connectionTimeout short (ST-HIKARI-001).
[INFO] Not evaluated: ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
[INFO] Not evaluated: ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml
[INFO] Report: <project>/target/springtune/springtune-report.md
[INFO] Report: <project>/target/springtune/springtune-report.sarif
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal io.github.winkytek.springtune:springtune-maven-plugin:0.4.1:check (default) on project orders-service: SpringTune: 4 finding(s) at or above WARNING. Fix them or suppress them with a reason in springtune.yml (or set -Dspringtune.failOn=NONE). -> [Help 1]
```

The plugin also writes `target/springtune/springtune-report.md` (paste it into a pull request) and
`target/springtune/springtune-report.sarif` (for code scanning).

**Options**, as `-D` properties or in the plugin's `<configuration>` ([details](docs/check.md#maven-plugin)):

| Property | Effect |
|---|---|
| `springtune.failOn` | `ERROR` (default), `WARNING`, `INFO` or `NONE` (report only) |
| `springtune.profiles` | Spring profiles to activate, e.g. `prod` |
| `springtune.formats` | Report files, default `markdown,sarif` |
| `springtune.skip` | `true` skips the check |

`springtune.yml` works as in [step 4 of the CLI](#1-cli).

---

## 3. Gradle plugin

**Step 1: apply the plugin** in `build.gradle.kts` (Gradle 8.5+, configuration-cache compatible):

<!-- snippet:gradle-plugin -->
```kotlin
plugins {
    id("io.github.winkytek.springtune") version "0.4.1"   // ./gradlew check now runs springtuneCheck
}
springtune { profiles.set(listOf("prod")) }            // optional; see docs/check.md#gradle-plugin
```

The project needs `mavenCentral()` (or a mirror) in `repositories`, where the plugin resolves its check engine.
The plugin is not listed on the Gradle Plugin Portal yet, but the Portal serves it from Maven Central, so the default
plugin repository works. If your build uses only a corporate mirror, add `mavenCentral()` to
`pluginManagement.repositories` in `settings.gradle.kts`.

**Step 2: run your normal build:**

```bash
./gradlew check
```

**Step 3: read the result.** On the demo service's sources (*trimmed*: Gradle's "Try" hints removed, the project
path shortened to `<project>`). The plugin reads the resolved runtime classpath, so dependencies declared in
version catalogs or convention plugins count:

```console
$ ./gradlew check
> Task :springtuneCheck
SpringTune 0.4.1: 5 finding(s), 0 suppressed
WARNING ST-HTTP-001 src/main/java/com/example/orders/InventoryClient.java:10:39: new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite). Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
WARNING ST-HIKARI-002 src/main/resources/application.yml:1: leakDetectionThreshold is not set (HikariCP default 0 = disabled). Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.
INFO ST-TX-001 src/main/resources/application.yml:1: No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run. Fix: Set spring.transaction.default-timeout (e.g. 30s) and/or a JDBC query timeout (spring.jdbc.template.query-timeout, or jakarta.persistence.query.timeout for JPA).
WARNING ST-HIKARI-001 src/main/resources/application.yml:9:27: connectionTimeout is 30000 ms, above the 5000 ms threshold. Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).
WARNING ST-TOMCAT-001 src/main/resources/application.yml:15:12: Tomcat may run 400 request threads against 10 pooled connections, a ratio of 40:1 (threshold 10:1). Fix: Bound concurrency to what the database can serve: lower server.tomcat.threads.max, or raise the pool within the connection budget (ST-HIKARI-004). Keep connectionTimeout short (ST-HIKARI-001).
Not evaluated: ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
Not evaluated: ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml
SpringTune report: file://<project>/build/springtune/springtune-report.md
SpringTune report: file://<project>/build/springtune/springtune-report.sarif

> Task :springtuneCheck FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':springtuneCheck'.
> There was a failure while executing work items
   > A failure occurred while executing io.github.winkytek.springtune.gradle.CheckWorkAction
      > SpringTune: 4 finding(s) at or above WARNING in :springtuneCheck. Fix them or suppress them with a reason in springtune.yml (or run with -Pspringtune.failOn=NONE).
```

Reports go to `build/springtune/`. Options: `-Pspringtune.failOn=NONE`, `-Pspringtune.profiles=prod`,
`-Pspringtune.formats=sarif`, or the `springtune { }` block ([details](docs/check.md#gradle-plugin)).

---

## 4. GitHub Action

**Step 1: add a workflow.** Findings appear as code-scanning alerts on the exact lines of the pull request. The
Action downloads the CLI from the GitHub release and runs it only if its SHA-256 matches the release's
`SHA256SUMS`:

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

**Step 2: open a pull request.** You get:

- the job log, with the same text report as [the CLI](#1-cli);
- the job's result: it fails on findings at or above the fail level (`fail-on` input, or `failOn` in
  `springtune.yml`);
- one code-scanning alert per finding, on its file and line, with the reason and the fix.

The Action's outputs are `exit-code` and `sarif`. The same workflow, with every input commented:
[examples/github-workflow.yml](examples/github-workflow.yml) ([more](docs/ci-integration.md)). SpringTune's own CI
runs the Action on the demo service on every push and checks that it fails with exactly the five findings above.

---

## 5. Spring Boot starter

The starter runs inside your application, watches its HikariCP pools and `ThreadPoolTaskExecutor`s, and explains
what it would change and why. It **changes nothing** (the default mode is `advise`).

**Step 1: add the dependency.** It brings Spring Boot Actuator with it.

<!-- snippet:maven-starter -->
```xml
<dependency>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.4.1</version>
</dependency>
```

Gradle: `implementation("io.github.winkytek.springtune:springtune-spring-boot-starter:0.4.1")`

**Step 2: expose the endpoint** in `application.yml`. The budget is optional but recommended: it caps any "raise
the pool" advice so that all replicas together stay within the database's connection limit.

```yaml
management.endpoints.web.exposure.include: health,tuning
springtune.budget:              # optional: caps pool advice at floor(100 x 0.8 / 6) = 13 per instance
  database-max-connections: 100
  max-replicas: 6
```

Secure `/actuator/tuning` like any other Actuator endpoint: it shows pool names and settings.

**Step 3: start the app and read the endpoint:**

```bash
curl -s localhost:8080/actuator/tuning
```

Right after startup there is no recommendation yet: a rule decides only after watching a full window (5 minutes by
default). The live values are there from the first sample (the sample app 12 s after startup, *trimmed* to the pool):

```json
{
  "mode": "ADVISE",
  "budget": {
    "databaseMaxConnections": 100,
    "maxReplicas": 6,
    "safetyFactor": 0.8,
    "perInstanceCap": 13
  },
  "components": [
    {
      "id": "hikari:orders-pool",
      "samples": 6,
      "signals": {
        "active": 0,
        "idle": 20,
        "waitingThreads": 0,
        "maximumPoolSize": 20
      }
    }
  ],
  "recommendations": []
}
```

**Step 4: read the advice under load.** The same kind of traffic gets a different answer depending on what the
signals show. Captured on the [sample app](springtune-samples/broken-config) (budget cap 13) under `curl` load, with
the window shortened to 1 minute (`springtune.hikari.window=1m`) so each case takes a minute; everything else uses
the defaults:

| Case | Pool | Load (callers × time each holds a connection) | `GET /actuator/tuning` says |
|---|---|---|---|
| Pool is the bottleneck, budget allows more | 8 | 40 × 50 ms | `ST-REC-HIKARI-001` **INCREASE 8 → 10**, and exactly what to commit |
| Pool is the bottleneck, already over budget | 20 | 60 × 50 ms | `ST-REC-HIKARI-001` **HOLD**: do not raise, the pool is above the cap of 13 |
| Slow queries | 20 | 60 × 2 s | `ST-REC-HIKARI-002` **HOLD**: do not raise, the database work is the bottleneck |

<details open><summary><b>INCREASE 8 → 10</b> (trimmed to recommendations and the apply plan)</summary>

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
        "meanAcquireTime": "193 ms",
        "slowHoldThreshold": "500 ms",
        "budgetCap": "13"
      },
      "confidence": "HIGH"
    }
  ],
  "applyPlans": [
    {
      "ruleId": "ST-REC-HIKARI-001",
      "from": 8,
      "to": 10,
      "commit": [
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=10"
      ],
      "budget": [
        "per-instance cap = floor(database-max-connections x safety-factor / max-replicas) = floor(100 x 0.8 / 6) = 13",
        "10 <= cap 13: within budget",
        "fleet at max replicas: 10 x 6 = 60 of 80 usable connections (this service only)",
        "server limit not checked: SpringTune cannot read the connection limit of H2; database-max-connections is not checked against the server"
      ],
      "sideEffects": [
        "minimumIdle equals maximumPoolSize (8, a fixed-size pool, Hikari's default): it moves to 10 as well, otherwise the pool would silently become dynamic.",
        "Hikari opens the additional connections on demand, as threads wait for one."
      ]
    }
  ]
}
```
</details>

<details><summary><b>HOLD: over the budget cap</b> (trimmed)</summary>

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
        "meanAcquireTime": "90 ms",
        "slowHoldThreshold": "500 ms",
        "budgetCap": "13"
      },
      "confidence": "MEDIUM"
    }
  ]
}
```
</details>

<details><summary><b>HOLD: slow queries</b> (trimmed)</summary>

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
        "samples": "31",
        "samplesWithWaitingThreads": "100%",
        "maxWaitingThreads": "40",
        "meanConnectionHoldTime": "2000 ms",
        "meanAcquireTime": "4005 ms",
        "slowHoldThreshold": "500 ms"
      },
      "confidence": "HIGH"
    }
  ]
}
```
</details>

How to read a recommendation:

| Field | Meaning |
|---|---|
| `ruleId` | The rule that fired; all rules are listed in [docs/starter.md](docs/starter.md#recommendation-rules) |
| `action` | `INCREASE`, `DECREASE`, or `HOLD` (an explicit "do not change this, here is why") |
| `reason` | The explanation in plain language |
| `evidence` | The measured values behind it, over the window |
| `confidence` | `HIGH`, `MEDIUM` or `LOW` |
| `applyPlans[].commit` | The exact properties to commit if you agree |

**Step 5: tell us what you did with it.** Each recommendation you accept, edit or reject is worth one
[recommendation feedback](https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml)
issue. Running it in staging for two weeks: [design partners](docs/design-partners.md). Full reference: the
[starter docs](docs/starter.md) (rules, properties, executors, the experimental apply mode) and a
[Grafana dashboard](docs/grafana/README.md).

> Coming in the next release (on `main`, not in 0.4.1): a per-rule `evaluations` status that says whether an empty
> `recommendations` means "healthy", "still collecting" or "metric missing", and `GET /actuator/tuning/report`, a
> version you can paste into an issue ([details](docs/starter.md#evaluation-status-is-an-empty-recommendations-healthy-after-041)).

### Optional: adaptive limiter

A separate artifact, `springtune-limiter`, off by default. It returns a fast `503` with `Retry-After` instead of
queueing when a downstream saturates. It starts in shadow mode, where it only counts what it would reject. In the
module's load test (160 callers, downstream capacity 10) it cut the server-side p99 of accepted requests from
351–435 ms to 70–108 ms. Read [docs/limiter.md](docs/limiter.md) (when it hurts, retry budgets, rollout) before
enforcing.

```yaml
springtune.limiter.enabled: true     # shadow; add springtune.limiter.enforce: true after the rollout steps
```

---

## Real projects: what the check found in 20 popular Spring Boot projects

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

Found something, or a false positive? [Open an issue](https://github.com/pvgiang/spring-tune/issues/new/choose)
(forms for false positives, rule requests, recommendation feedback, fleet/approval needs and bugs): every false
positive becomes a regression test.

## Documentation

**Using SpringTune**
- [The check: CLI, Maven and Gradle plugins, how configuration is resolved, limitations](docs/check.md)
- [`springtune.yml` reference](docs/configuration.md) · [Rules](docs/rules/README.md) · [CI integration](docs/ci-integration.md)
- [Runtime starter](docs/starter.md) · [Design partners: two weeks in staging](docs/design-partners.md) · [Grafana dashboard](docs/grafana/README.md)
- [Adaptive limiter](docs/limiter.md)

**Evidence**
- [Case study: 20 popular Spring Boot projects](docs/case-study/what-we-found-in-20-spring-boot-projects.md) · [Corpus report: method, per-rule precision, raw results](docs/corpus/2026-11-corpus-report.md)
- Rule deep dives: [connection acquire timeout (ST-HIKARI-001)](docs/deep-dives/ST-HIKARI-001-connection-timeout.md),
  [HTTP clients without timeouts (ST-HTTP-001)](docs/deep-dives/ST-HTTP-001-http-client-timeouts.md),
  [transactions and queries without a timeout (ST-TX-001)](docs/deep-dives/ST-TX-001-transaction-query-timeouts.md)
- [PetClinic benchmark: what the starter recommends under load (pre-registered; 2 of 4 matched as registered, 1 more only with a poll bound added afterwards, 1 miss)](docs/benchmarks/petclinic.md)
- [Load tests: apply mode against a real PostgreSQL](docs/loadtests.md) · [Overhead benchmarks (JMH)](docs/benchmarks.md)

**Project**
- [Release notes](docs/releases/) · [Roadmap (current release: 0.4.1)](docs/roadmap.md) · [Architecture](docs/architecture.md)
- [Adoption gates: what we measure and how (no telemetry)](docs/adoption/gates.md) · [Releasing](docs/releasing.md)
- Delivery briefs: [1](docs/delivery/iteration-1-brief.md), [2](docs/delivery/iteration-2-brief.md), [3](docs/delivery/iteration-3-brief.md), [4](docs/delivery/iteration-4-brief.md), [5](docs/delivery/iteration-5-brief.md), [6](docs/delivery/iteration-6-brief.md) · [Business & implementation plan](docs/business-and-implementation-plan.md)
- [Contributing](CONTRIBUTING.md) (build, tests, adding a rule, DCO sign-off) · [Security policy](SECURITY.md) · [Code of conduct](CODE_OF_CONDUCT.md)

## Build from source

```bash
mvn -B verify                                              # all modules, unit + integration tests, plugin ITs
mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip  # Spring Boot 3.5 compatibility
```

JDK 17+ and Maven 3.9+. The Gradle plugin's functional tests run real Gradle builds: they use
`-Dspringtune.it.gradle=<gradle or GRADLE_HOME>`, else `gradle` on `PATH`, and are skipped without one. CI
(`.github/workflows/ci.yml`) runs JDK 17/21/25, Boot 4.1 and 3.5, Gradle 8.14 and 9.8, and the GitHub Action on the
demo. Releases: [releasing](docs/releasing.md).

To try both parts from a source build, with the deliberately broken [sample app](springtune-samples/broken-config):

```bash
mvn -q install -DskipTests -Dinvoker.skip
java -jar springtune-check-cli/target/springtune-check-cli-*-all.jar check springtune-samples/broken-config
java -jar springtune-samples/broken-config/target/springtune-sample-broken-config-*.jar &
curl -s localhost:8080/actuator/tuning
curl -s 'localhost:8080/orders?holdMs=50'      # each call holds a connection this long; load it to see advice
```

| Module | Purpose |
|---|---|
| `springtune-model` | Shared domain: `Finding`, `RuleDescriptor`, `Knob`, `Recommendation`, `ConnectionBudget` |
| `springtune-check-engine` | Config resolution, scanners, rule API, 13 rules, reporters |
| `springtune-check-cli` | `springtune check` / `springtune rules` (runnable `-all.jar`) |
| `springtune-maven-plugin` | `mvn springtune:check`, bound to `verify` |
| `springtune-gradle-plugin` (+ `-marker`, `-tests`) | Plugin `io.github.winkytek.springtune`: `./gradlew springtuneCheck`, wired into `check` |
| `springtune-spring-boot-autoconfigure` / `-starter` | Runtime registry, sampler, recommendations, `/actuator/tuning` |
| `springtune-limiter` | Opt-in adaptive concurrency limiter (`Filter`/`WebFilter` over Netflix concurrency-limits) |
| `springtune-samples/broken-config` | Runnable demo and end-to-end fixture |
| `action.yml` | GitHub Action wrapping the CLI (verifies the jar against the release's `SHA256SUMS`) |
| `examples/broken-config-ci` | Standalone demo service: the check fails on four findings, with the fix for each |

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attributions. Every published jar carries both files in
`META-INF/`.
