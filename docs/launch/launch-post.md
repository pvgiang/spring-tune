<!--
Launch post draft (posting is a human step). Every number below is traced in docs/launch/claims.md.
Before posting: docs/launch/checklist.md, and scripts/content/check-claims.sh must pass.
Links are absolute so the text can be pasted elsewhere; they work only once the repository is public.
-->

# We scanned 20 popular Spring Boot projects for the config behind a common outage

A common kind of Spring Boot outage is not exotic at all. It follows one pattern: something downstream gets
slow, and a default nobody chose turns "slow" into "down".

## The pattern

A service runs on Spring Boot's defaults: 200 Tomcat request threads, a HikariCP pool of 10 connections, and
HikariCP's default `connectionTimeout` of 30 seconds. One afternoon the database has a bad minute (a failover,
a lock, a vacuum), and a query that took 30 ms takes 3 s.

The pool can now serve about 3 requests per second instead of about 330. Requests keep arriving at 50 per
second, so about 47 more threads block every second, each waiting up to 30 s for a connection. After about
4 seconds all 200 request threads are waiting. `/actuator/health` stops answering too, the orchestrator restarts
the pod, the new pod inherits the same queue, and callers retry, adding load. The same thing happens with an HTTP
client that has no read timeout: one hung dependency holds every thread that calls it. (The arithmetic is
illustrative, Little's law on the defaults, not a measurement of any project.)

None of this is visible in code review. The 30 s is not in your YAML; it is the absence of a line.

## What we found in 20 open-source projects

We ran `springtune check`, a static check for this class of configuration, over 20 well-known public Spring Boot
repositories: Spring PetClinic (and its microservices and REST variants), JHipster's sample app, Apache
DolphinScheduler, Apache ShenYu, ThingsBoard, JeecgBoot, RuoYi, mall, halo and others. That is 282 Spring Boot
modules. Then the author reviewed the findings.

- **Every HikariCP module we could evaluate, 45 of 45 in 12 of the 20 repositories, waits more than 5 s for a
  connection when the pool runs dry, and none has leak detection on.** 44 of them wait up to 30 s (38 simply by
  not setting `connection-timeout`), one waits 10 s. Apache DolphinScheduler's API server is a typical example.
  25 of the 45 are in two tutorial collections, so read "12 repositories" as the headline, not "45". Another 18
  HikariCP modules were not evaluated: their pool is built in code or their config lives outside the repository.
- **6 HTTP clients built without timeouts, in 5 repositories**: 3 `new RestTemplate()` (connect and read
  timeout infinite), 2 `WebClient.create(...)` (no response timeout), 1 `RestClient.builder().build()`. They
  include the Spring PetClinic microservices API gateway (`@LoadBalanced new RestTemplate()`) and its GenAI
  service. One of the 6 is low-value: a client that runs only in a dev profile.
- **7 executors with unbounded queues in 5 repositories**, including JeecgBoot's AI chat streams on
  `Executors.newFixedThreadPool(10)`: slow LLM calls queue in memory instead of being rejected.
- **70 modules in 16 repositories with no transaction or query timeout** (info level).
- **199 findings in total, 1 false positive (0.5%), reviewed by the author.** The first run was worse: at least
  61 of its 307 findings were wrong (Spring Boot 1.x apps on tomcat-jdbc, Druid-owned datasources, config living
  in a Config Server). We fixed the tool, not the numbers: each of those patterns is now a regression test, and
  where the deciding config is not in the repository the check says *not evaluated, and why* instead of guessing.

These are excellent, widely copied projects. That is the point: the defaults bite everyone.

The numbers above come from a run at pinned commits from 2026-11-03, re-run with the released 0.4.0 CLI on
2026-11-09 with identical results, finding for finding. Method, per-rule precision and every raw JSON report:
[corpus report](https://github.com/pvgiang/spring-tune/blob/main/docs/corpus/2026-11-corpus-report.md),
[0.4.0 re-run](https://github.com/pvgiang/spring-tune/blob/main/docs/corpus/2026-11-09-0.4.0-rerun.md), and the
longer [case study](https://github.com/pvgiang/spring-tune/blob/main/docs/case-study/what-we-found-in-20-spring-boot-projects.md)
with the code and the fixes.

## Try it on your repository (0.4.1)

It is static only: nothing is built or executed, and nothing leaves the machine. The default threshold is ERROR,
and the ERROR rules need your database limits, so these snippets use `WARNING` to turn the findings above into a
red check.

**GitHub Action**, findings as code-scanning alerts on the exact line. It runs the CLI jar only if its SHA-256
matches the release's `SHA256SUMS`:

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
          version: "0.4.1"         # pass it: the v0.4.1 tag defaults to CLI 0.4.0
          fail-on: WARNING
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: ${{ steps.springtune.outputs.sarif }}
```

**Maven** (Maven Central, groupId `io.github.winkytek.springtune`), then `mvn verify -Dspringtune.failOn=WARNING`:

```xml
<plugin>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>0.4.1</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

**Gradle** (8.5+), then `./gradlew springtuneCheck`:

```kotlin
plugins {
    id("io.github.winkytek.springtune") version "0.4.1"
}
repositories { mavenCentral() }
springtune { failOn.set("WARNING") }
```

**CLI** (Java 17+), from Maven Central, checksum first:

```bash
curl -fsSLO https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1-all.jar
echo "3f3726f752ae7605ee0687ac9a1f2158585928bc593cb90b95d45e4b140b599e  springtune-check-cli-0.4.1-all.jar" | sha256sum -c -
java -jar springtune-check-cli-0.4.1-all.jar check . --fail-on WARNING
```

Want to see it fail before touching your own repository? The
[broken-config demo](https://github.com/pvgiang/spring-tune/tree/main/examples/broken-config-ci) is a small
project with exactly these mistakes and a workflow that turns them into a red check.

To accept a finding, suppress it in `springtune.yml` with a reason (the reason is required and shows up in the
report).

## What it is not

- **It is a CI check plus read-only advice.** That is what this post is about. There is also a runtime starter
  that exposes `/actuator/tuning` with live pool and executor values and explained recommendations; it is
  read-only by default.
- **Apply mode is experimental and opt-in.** The starter can apply one reviewed pool change, verify it and revert
  it. Before writing any load test we pre-registered six bars it must meet before it stops being experimental.
  Five of six are met (among them three replicas raising at once on a real PostgreSQL without exceeding the
  connection budget, and a failing database where apply is refused). The first bar, a design partner running it in
  production, is not: **nobody runs SpringTune in production that we know of.** The bars are written down in the
  [iteration-4 brief, §2](https://github.com/pvgiang/spring-tune/blob/main/docs/delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code).
- **It does not see config that is not in the repository.** Modules whose config lives in a Config Server or
  Nacos, or whose pool is built in code, get *not evaluated* with the reason. That costs recall; we prefer it to
  guessing.
- **13 rules, one topic**: connection pools, timeouts, executors, retries and circuit breakers. Not a general
  linter.
- **No telemetry.** We learn what it finds only if you tell us.

## Tell us what it found

[Open an issue](https://github.com/pvgiang/spring-tune/issues/new/choose): there are forms for false positives
and rule requests. Every false positive we hear about becomes a regression test.
