# What we found scanning 20 popular Spring Boot projects

We pointed `springtune check` at 20 well-known open-source Spring Boot repositories (Spring PetClinic,
JHipster, Apache DolphinScheduler, Apache ShenYu, ThingsBoard, JeecgBoot, RuoYi, mall and more: 282 Spring
Boot modules) and read every finding by hand.

**TL;DR**

- **All 45** HikariCP services we could fully evaluate make a request wait longer than 5 seconds for a database
  connection when the pool runs dry: 44 **up to 30 seconds** (38 on the unset default, 6 with `30000` written out)
  and 1 for 10 seconds. **None** of them would tell you about a connection leak. (Counts from the
  [0.4.0 re-run](../corpus/2026-11-09-0.4.0-rerun.md).)
- Inter-service HTTP calls with **no timeout at all**, in the Spring PetClinic microservices reference
  architecture among others: one hung downstream service can freeze the caller.
- **0.5% false positives** (1 in 199 findings). When the check cannot know something, it says so instead of
  guessing.

These are excellent, widely copied projects. That is the point: these defaults bite everyone, because
nothing tells you about them until production does.

---

## 1. The 30-second wait nobody chose

**What we saw.** HikariCP's default `connectionTimeout` is 30,000 ms, and leak detection is off by default.
We found HikariCP services in 12 of the 20 repositories. Of the 45 we could evaluate, 38 leave
`connectionTimeout` unset, 6 set it to `30000` explicitly and 1 (`jeecg-cloud-xxljob` in JeecgBoot) sets `10000`;
all 45 leave leak detection off ([0.4.0 re-run](../corpus/2026-11-09-0.4.0-rerun.md)). Here is Apache DolphinScheduler's API server
([`application.yaml#L49-L56`](https://github.com/apache/dolphinscheduler/blob/282d956cbc11215b30112e59a69780b238366f34/dolphinscheduler-api/src/main/resources/application.yaml#L49-L56)):

```yaml
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://127.0.0.1:5432/dolphinscheduler
    username: root
    password: root
    hikari:
      connection-test-query: select 1
      pool-name: DolphinScheduler
```

The `hikari` block has no `connection-timeout` (so 30 s applies) and no `leak-detection-threshold` (so leaks
go unreported). There's nothing unusual about it: it is what almost every Spring Boot service looks like.

**Why it takes you down.** Take Spring Boot's own defaults: 200 Tomcat threads, a 10-connection pool, and a
30-second wait. 25 modules in 7 of these repositories run exactly that combination (ST-TOMCAT-001). Now the
database has a bad minute, and a query that took 30 ms takes 3 s:

| | Normal | Database slow |
|---|---|---|
| Requests the pool can serve (10 connections ÷ query time) | ~330/s | **~3/s** |
| Incoming requests | 50/s | 50/s |
| Threads stuck waiting for a connection | 0 | grows by **~47 every second** |
| All 200 Tomcat threads blocked after | never | **~4 seconds** |

Within seconds the service stops answering everything, including `/actuator/health`. Kubernetes restarts
the pod, the replacement inherits the same queue, and callers retry, which adds load. A brief database
hiccup has turned into an outage. A leaked connection, one borrowed and never returned, does the same thing
slowly, with no stack trace to say where it leaked.

**What SpringTune says** about `dolphinscheduler-api` (real output, `Docs:` links omitted):

```
WARNING ST-HIKARI-001  dolphinscheduler-api/src/main/resources/application.yaml:1
        connectionTimeout is not set, so HikariCP waits up to 30000 ms (default) for a connection.
        Fix: Set spring.datasource.hikari.connection-timeout below your upstream request timeout, at most 5000 ms (e.g. 2000-5000).

WARNING ST-HIKARI-002  dolphinscheduler-api/src/main/resources/application.yaml:1
        leakDetectionThreshold is not set (HikariCP default 0 = disabled).
        Fix: Set spring.datasource.hikari.leak-detection-threshold slightly above your longest legitimate connection hold time, e.g. 30000-60000 ms. HikariCP then logs the borrowing stack trace.

INFO    ST-TX-001  dolphinscheduler-api/src/main/resources/application.yaml:1
        No transaction or query timeout is configured: a slow query holds its pooled connection for as long as the database lets it run.

Not evaluated (2):
  - [dolphinscheduler-api] ST-HIKARI-003 not evaluated: declare databases[].idleTimeout (e.g. MySQL wait_timeout) in springtune.yml
  - [dolphinscheduler-api] ST-HIKARI-004 not evaluated: declare databases[].maxConnections in springtune.yml
```

Note the last two lines. The two rules that need your database's limits don't guess; they tell you what
input would turn them on.

**The fix is three lines:**

```yaml
spring.datasource.hikari:
  connection-timeout: 3000           # fail fast, well below your upstream timeout
  leak-detection-threshold: 60000    # log the stack trace of any connection held > 60 s
```

Then, if you give SpringTune your database's `max_connections` and replica counts, **ST-HIKARI-004**
checks the whole fleet: *do all your pools, times all your pods, fit in the database?* That is the
"we scaled to 20 pods and Postgres refused connections" incident, caught in CI.

## 2. HTTP calls that never give up

**What we saw.** The Spring PetClinic microservices API gateway calls the other services with a load-balanced
`RestTemplate` built with no timeouts
([`ApiGatewayApplication.java#L52-L56`](https://github.com/spring-petclinic/spring-petclinic-microservices/blob/e8b747ab522721076cacb5cf688eb8a54ba8c5c9/spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/ApiGatewayApplication.java#L52-L56)):

```java
@Bean
@LoadBalanced
RestTemplate loadBalancedRestTemplate() {
    return new RestTemplate();   // SimpleClientHttpRequestFactory: connect and read timeout infinite
}
```

The same project's GenAI service does it with the new API too: `RestClient.builder().build()`
([`AIDataProvider.java#L37`](https://github.com/spring-petclinic/spring-petclinic-microservices/blob/e8b747ab522721076cacb5cf688eb8a54ba8c5c9/spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/AIDataProvider.java#L37)).
We found the same pattern in the FTGO gateway (`WebClient.create()`), in mall and in an OAuth demo.

**Why it takes you down.** If the customers service hangs (a GC pause, a stuck lock, a half-open TCP
connection), every gateway thread that calls it waits forever. A circuit breaker doesn't help either: a call
that never returns is neither a failure nor "slow" until it finishes (see ST-R4J-002).

**What SpringTune says** (real output, `Docs:` link omitted):

```
WARNING ST-HTTP-001  spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/ApiGatewayApplication.java:55:16
        new RestTemplate() uses SimpleClientHttpRequestFactory with no connect or read timeout (infinite).
        Fix: Build it with RestTemplateBuilder.connectTimeout(...).readTimeout(...), or pass a request factory with timeouts set.
```

**The fix:**

```java
@Bean @LoadBalanced
RestTemplate loadBalancedRestTemplate(RestTemplateBuilder builder) {
    return builder.connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(3)).build();
}
```

## 3. Unbounded queues behind AI features

**What we saw.** JeecgBoot's AI module runs two features, resuming a chat stream and generating AI posters,
on a fixed pool of 10 threads with an **unbounded** queue
([`AiragChatServiceImpl.java#L147`](https://github.com/jeecgboot/JeecgBoot/blob/a4d5234755a60f8386d030a36568e81ecdbbc24b/jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java#L147)):

```java
private static final ExecutorService SSE_THREAD_POOL = Executors.newFixedThreadPool(10); // max 10 threads
```

**Why it hurts.** LLM calls are slow. When requests arrive faster than 10 threads can finish them, they don't
fail, they queue, without limit. Users see their requests time out on the client while the server keeps
doing the work, and memory grows until the JVM is in trouble. Nothing pushes back. We found 7 executors like
this in 5 repositories (ST-ASYNC-002), plus `@Async` running on Spring Boot's default unbounded queue in
litemall (ST-ASYNC-001).

**The fix:** bound it and decide what happens when it's full.

```java
new ThreadPoolExecutor(10, 10, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
        new ThreadPoolExecutor.AbortPolicy());   // or CallerRunsPolicy; reject early, return 429/503
```

## 4. Slow queries that hold connections forever

70 modules in 16 repositories have **no transaction or query timeout** (ST-TX-001, info level). A single
runaway report query can hold its connection for as long as the database allows, and a few of them drain the
pool. That's finding 1 again, from another angle. `spring.transaction.default-timeout: 30s` is the fix.

---

## What it deliberately did *not* flag

A check that cries wolf gets switched off. Our first run over this corpus produced 307 findings, and we
reviewed them. At least 61 were wrong, so we fixed the tool rather than the numbers:

- **Apps using Druid, dynamic-datasource, or Spring Boot 1.x** (default pool: tomcat-jdbc) no longer get
  HikariCP advice.
- **Config that lives in a Config Server or Nacos:** a missing property proves nothing, so the module is
  reported as *not evaluated, and here's why*.
- **`@EnableAsync` with no `@Async` methods** is silent, and an `AsyncConfigurer` in a shared library module
  is found by following the Maven/Gradle dependency graph.
- **Feign clients routed through Ribbon** (1 s default) are not told they have a 60 s timeout.

Final run: **199 findings, 1 false positive.** Every false-positive pattern we found is now a regression
test. Full method, per-rule precision and raw reports are in the
[corpus report](../corpus/2026-11-corpus-report.md).

## Run it on your repo in 60 seconds

**On every pull request** (findings appear as code-scanning alerts on the exact line):

```yaml
# .github/workflows/springtune.yml
on: [pull_request]
permissions: { contents: read, security-events: write }
jobs:
  springtune:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pvgiang/spring-tune@v0.4.0
        id: springtune
        with: { version: "0.4.0" }   # the CLI to run; the v0.4.0 tag defaults to 0.1.0
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with: { sarif_file: "${{ steps.springtune.outputs.sarif }}" }
```

**Or locally, right now:**

```bash
curl -fsSLO https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.0/springtune-check-cli-0.4.0-all.jar
java -jar springtune-check-cli-0.4.0-all.jar check . --profile prod
```

- **Static only:** nothing is built or executed, and nothing leaves your machine.
- **Accepting a finding:** suppress it in `springtune.yml`, with a reason (required). The reason shows up in
  the report and in code scanning.
- **Fleet-wide connection budget:** add your database's `max_connections` and replica counts, and the check
  covers the whole fleet (ST-HIKARI-004).

**Tell us what it found.** Open an issue with a rule request or a false positive. Every false positive we
hear about becomes a regression test.

---

*Method: shallow clones at the commits listed in
[`shas.tsv`](../corpus/results/2026-11-03/shas.tsv), default settings, no `springtune.yml`, each repo's own
active profiles. The arithmetic in section 1 is illustrative (Little's law with Spring Boot defaults), not a
measurement of these projects.*
