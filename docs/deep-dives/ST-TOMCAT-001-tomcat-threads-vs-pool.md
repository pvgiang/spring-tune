# 200 request threads, 10 connections: why the health check stops answering when the database slows down

*Deep dive #4 · rule [ST-TOMCAT-001](../rules/ST-TOMCAT-001.md) · corpus numbers from SpringTune 0.4.0, install
lines 0.4.1 · 2026-11-15 · numbers traced in [docs/launch/claims.md](../launch/claims.md#deep-dive-4-st-tomcat-001)*

Out of the box, Spring Boot's embedded Tomcat runs up to 200 request threads, and HikariCP opens at most 10
connections. That is 20 threads per connection. On a normal day the difference is invisible. When the database
slows down, up to 190 of those threads can park in the pool waiting for a connection, and requests that never
touch the database, health checks included, find no thread left to run on.

## How it goes wrong

The story below is a composite of a common incident pattern, not a report about any real company. The numbers are
illustrative (Little's law on the defaults), not a measurement.

A catalogue service runs on Spring Boot's defaults: `server.tomcat.threads.max` 200, HikariCP
`maximum-pool-size` 10, `connectionTimeout` 30 s. It serves 100 requests per second. 40 of them read the database,
with queries that take about 20 ms, so fewer than one connection is busy on average. The other 60 are served from
a cache, plus the orchestrator's `/actuator/health` probe.

| Time | What happens |
|---|---|
| 0 s | A long-running migration locks a table. Queries that took 20 ms now take 2 s. |
| 0 to 5 s | 10 connections at 2 s each serve 5 database requests per second. The other 35 per second take a Tomcat thread and park in `getConnection()`, each for up to 30 s. 35 more parked threads every second. |
| about 5.4 s | 10 threads hold connections and 190 wait in the pool (190 / 35 ≈ 5.4). All 200 threads are busy. The 60 cached requests per second, which need no connection at all, now wait in Tomcat's accept queue behind them. |
| about 10 s | The health probe gets no thread and times out. The orchestrator marks the pod unready and, a few failed probes later, restarts it. |
| 30 s | The first parked requests fail with `SQLTransientConnectionException`. Their threads are freed and are taken at once by new database requests, which park in turn. The cached endpoints stay down for as long as the lock lasts. |

The lock was one table. The outage was the whole service, because the number of threads that may wait for a
connection was not bounded by anything except Tomcat's own maximum.

The same story with a bounded configuration: `threads.max` 100, `maximum-pool-size` 20 (if the database's
connection budget allows it, see [ST-HIKARI-004](../rules/ST-HIKARI-004.md)), `connectionTimeout` 2 s, and the
actuator on its own port. The pool now serves 10 slow database requests per second; the other 30 per second wait
at most 2 s each, so about 60 threads are parked at any moment (30 × 2). That leaves 20 of the 100 threads for the
cached requests, which take a few milliseconds each and so keep only a few threads busy. The database screens fail fast with
a clear error, the rest of the service keeps answering, and the health probe runs on a separate server.

## How often it shows up

In our [corpus of 20 public Spring Boot repositories](../corpus/2026-11-corpus-report.md) (re-run with the released
0.4.0 CLI, [identical results](../corpus/2026-11-09-0.4.0-rerun.md)), ST-TOMCAT-001 reported **25 modules in 7
repositories**, with **0 false positives** in the author's review. All 25 are **INFO**, and all 25 say the same
thing: `Tomcat may run 200 request threads (default) against 10 pooled connections (default), a ratio of 20:1
(threshold 10:1).` No module in the corpus set either value to a mismatch of its own; every finding is the default
case.

- **16 of the 25** are in the two tutorial collections (`ityouknow/spring-boot-examples` 11,
  `xkcoding/spring-boot-demo` 5).
- **The other 9** are in 5 repositories: FTGO 5, and 1 each in the RealWorld example app, the JHipster sample app,
  Spring PetClinic REST and Spring PetClinic.
- **All 25 were also flagged by [ST-HIKARI-001](ST-HIKARI-001-connection-timeout.md)**: every module that can park
  190 threads in the pool also lets each of them wait 30 seconds there.

The rule looked at **38 modules** in which the check detected both HikariCP and embedded
Tomcat, in 10 repositories. The 13 it did not flag:

- **4 set a larger pool**, which puts them inside the ratio: three set `maximum-pool-size: 20` (200 threads ≤ 20 ×
  10, exactly at the threshold) and one sets 30. They are `demo-orm-jdbctemplate` and `demo-task-quartz` in
  `xkcoding/spring-boot-demo`, Apache ShenYu's `shenyu-admin`, and JeecgBoot's `jeecg-cloud-xxljob` (pool 30).
- **5 build their DataSource in code**, so `spring.datasource.hikari.*` may not apply; the check reports every
  HikariCP rule for them as *not evaluated* instead of guessing.
- **4 keep their configuration in a Config Server** (the Spring PetClinic microservices' customers, vets, visits
  and GenAI services): *not evaluated*, because the local files are not the configuration that runs.

Another 25 HikariCP modules in the corpus were not considered at all, because the check did not detect embedded
Tomcat in them (not a servlet web module, or Jetty or Undertow).

One real example: [Spring PetClinic](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/resources/application.properties)
at the pinned commit sets neither value, so it runs the 200:10 default. For a sample application that is a fine
choice. Copied into a service with real traffic, it is the story above.

## The fix

Bound how many request threads can wait for a connection, and make each wait short:

```yaml
server:
  tomcat:
    threads:
      max: 100                    # at most 10 threads per connection with the pool below
spring:
  datasource:
    hikari:
      maximum-pool-size: 20       # only within the database's connection budget, across all replicas
      connection-timeout: 2000    # ms; a request that cannot get a connection fails fast (ST-HIKARI-001)
management:
  server:
    port: 8081                    # actuator on its own server and threads: probes keep answering
```

How to pick the values:

- **Start from the database, not from Tomcat.** The pool size times the number of replicas (plus every other
  client) must stay under the database's connection limit. Raising the pool is not free; often the right move is
  to lower `threads.max` instead.
- **Then size the threads to the pool.** The rule's default threshold is 10 threads per connection. If most of
  your requests do not need the database, a higher ratio can be right: raise the rule's `max-ratio` option, or
  suppress the finding for that one module, and write down why (below).
- **Keep the connection wait short** whatever the ratio. A ratio inside the threshold still lets every spare
  thread park for the full `connectionTimeout`; a short timeout is what turns that into a fast, visible error.

What happens at the limits: a request that waits longer than `connectionTimeout` gets an
`SQLTransientConnectionException` from HikariCP; map it to a 503. When all Tomcat threads are busy, new
connections wait in Tomcat's accept queue (`server.tomcat.accept-count`) and are refused when it is full, which a
load balancer can retry elsewhere.

## How the rule catches it

[ST-TOMCAT-001](../rules/ST-TOMCAT-001.md) (source:
[`TomcatThreadsVsPoolRule`](../../springtune-check-engine/src/main/java/io/github/winkytek/springtune/check/rules/TomcatThreadsVsPoolRule.java))
runs for a module when all of these hold:

- it uses HikariCP through Spring Boot's auto-configuration (not a DataSource built in code, not with
  `DataSourceAutoConfiguration` excluded) and its configuration files are in the repository;
- it runs on embedded Tomcat: `spring-boot-starter-web`, `-webmvc` or `-tomcat` without Jetty or Undertow, or any
  `server.tomcat.*` property;
- virtual threads are not enabled (`spring.threads.virtual.enabled: true` skips the rule).

It reads `server.tomcat.threads.max` (falling back to `server.tomcat.max-threads`, the Spring Boot 2.2 and earlier
key) and `spring.datasource.hikari.maximum-pool-size`, with the library defaults 200 and 10 for whichever is not
set. It reports when threads > pool × `max-ratio` (default 10). If either value is a placeholder it cannot
resolve, it reports nothing.

Severity follows who chose the numbers. **INFO** when both are library defaults: a risk worth knowing, reported at
the module's configuration file, line 1. **WARNING** when either is set explicitly, at the line that sets the
threads (or the pool): someone chose a mismatch, and the build can fail on it with `--fail-on WARNING`.

What it deliberately does not catch, or cannot see:

- **The ratio is a heuristic, not a capacity model.** It does not know what share of requests needs the database
  or how long they hold a connection. The three corpus modules at pool 20 pass exactly at 10:1 and can still park
  180 threads for 30 seconds; ST-HIKARI-001 flags them for that.
- **One pool per module.** A second DataSource, a pool built in code, or values set by environment variables at
  deploy time are not part of the comparison.
- **Other servers and models.** Jetty, Undertow, WebFlux and virtual threads are skipped: their thread models make
  a thread-to-connection ratio the wrong question.
- **Config outside the repository** (Config Server, Nacos) gives *not evaluated*, never a guess.

To raise the threshold (the option applies to every module), or to accept the finding for one module whose
requests rarely need the database, write the reason down:

```yaml
# springtune.yml
rules:
  ST-TOMCAT-001:
    options:
      max-ratio: 25         # for every module the check reads
suppress:
  - rule: ST-TOMCAT-001
    module: catalogue-service
    reason: "95% of requests are served from the cache; connectionTimeout is 2 s"
```

## See it fail

The [broken-config demo](../../examples/broken-config-ci/) sets `server.tomcat.threads.max: 400` against a pool of
10 and gets the WARNING version of this finding (40:1, at `application.yml:15:12`), next to its other findings.
To make the INFO default case fail your own build, run the check with `--fail-on INFO`:

```bash
java -jar springtune-check-cli-0.4.1-all.jar check . --fail-on INFO
```

The jar, its checksum, and the Maven, Gradle and GitHub Action setups are in the
[launch post](../launch/launch-post.md#try-it-on-your-repository-041).

## Related rules

- [ST-HIKARI-001](../rules/ST-HIKARI-001.md): how long each parked thread waits for a connection
  ([deep dive #1](ST-HIKARI-001-connection-timeout.md)).
- [ST-HIKARI-004](../rules/ST-HIKARI-004.md): the connection budget across replicas, which caps how far the pool
  can grow.
- [ST-TX-001](../rules/ST-TX-001.md): transaction and query timeouts, which decide how long a connection stays
  held ([deep dive #3](ST-TX-001-transaction-query-timeouts.md)).
