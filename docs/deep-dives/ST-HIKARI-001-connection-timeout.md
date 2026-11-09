# The 30-second wait nobody chose: HikariCP `connectionTimeout`

*Deep dive #1 · rule [ST-HIKARI-001](../rules/ST-HIKARI-001.md) · SpringTune 0.4.0 · numbers traced in
[docs/launch/claims.md](../launch/claims.md)*

When a HikariCP pool has no free connection, the thread that asked for one waits. How long it waits is
`connectionTimeout`, and HikariCP's default is **30,000 ms**. Almost no Spring Boot service sets it, so almost every
Spring Boot service will hold a request thread for half a minute when the database gets slow. That one number is
the difference between "the database had a slow minute" and "we were down for twenty".

## How it goes wrong

The story below is a composite of a common incident pattern, not a report about any real company. The numbers use
Spring Boot's defaults and Little's law; they are illustrative, not measured.

A service handles orders on Spring Boot's defaults: 200 Tomcat request threads, a pool of 10 connections,
`connectionTimeout` unset. Traffic is a steady 50 requests per second; each does one 30 ms query. The pool is
mostly idle: 10 connections can serve about 330 such requests per second.

At 14:02 the primary database fails over. For about a minute queries take 3 s instead of 30 ms.

| Time after the slowdown | What happens |
|---|---|
| 0 s | 10 connections now serve about 3 requests per second. 50 per second keep arriving. |
| 1 s | About 47 request threads are parked inside `HikariPool.getConnection()`, each willing to wait 30 s. |
| ~4 s | All 200 Tomcat threads are waiting for a connection. New requests queue in the accept backlog. |
| ~10 s | `/actuator/health` cannot get a thread either. The readiness probe fails; the load balancer removes the pod. |
| ~30 s | The liveness probe fails; the pod restarts. Its replacement gets the same traffic plus every client's retries. |
| 60 s | The database is healthy again. The service is not: it is restarting pods and draining retry storms. |

Nothing in this story is a bug in the code. The database recovered after one minute; the service was down for
much longer, because every thread was told to wait 30 s for something it could have given up on in 2.

What a short timeout changes: with `connection-timeout: 2000`, a request that cannot get a connection fails after
2 s with `SQLTransientConnectionException`. Threads come back. The service returns errors (map them to 503) for the
slow minute, health checks keep answering, nothing restarts, and it recovers when the database does. A fast
failure is a smaller outage than a slow one.

Its sibling is the **connection leak**: code that borrows a connection and never returns it shrinks the pool for
good, and the first symptom is the same exhaustion, with no stack trace pointing at the leak. HikariCP can log
the borrowing stack trace, but only if `leak-detection-threshold` is set; it is off by default
([ST-HIKARI-002](../rules/ST-HIKARI-002.md)).

## How often it shows up

In our [corpus of 20 public Spring Boot repositories](../corpus/2026-11-corpus-report.md) (re-run with the released
0.4.0 CLI, [identical results](../corpus/2026-11-09-0.4.0-rerun.md)):

- HikariCP was detected in **63 modules**. 18 could not be evaluated (11 build the pool in code, 7 load their
  config from a Config Server or Nacos) and were reported as *not evaluated*, not guessed.
- **All 45 evaluable modules, in 12 of the 20 repositories, were flagged.** 38 leave `connectionTimeout` unset (30 s
  by default), 6 set `30000` explicitly, 1 sets `10000`.
- **All 45 also have leak detection off.**
- 25 of the 45 are in two tutorial collections. The other 20 are the FTGO example application (7 modules), Apache
  DolphinScheduler (5) and one module each in Spring PetClinic, the PetClinic REST app, the RealWorld example app,
  JHipster's sample app, Apache ShenYu, mall, JeecgBoot and ThingsBoard.

A typical one, Apache DolphinScheduler's API server
([`application.yaml#L49-L56`](https://github.com/apache/dolphinscheduler/blob/282d956cbc11215b30112e59a69780b238366f34/dolphinscheduler-api/src/main/resources/application.yaml#L49-L56)):
the `hikari` block sets a pool name and a test query, and neither `connection-timeout` nor
`leak-detection-threshold`.

## The fix

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 2000          # fail fast: well below the caller's own timeout
      leak-detection-threshold: 60000   # log the borrowing stack trace of any connection held > 60 s
```

How to pick the value:

- **Below your upstream timeout.** If the gateway or client gives up after 5 s, a request that waits 30 s for a
  connection is work nobody will read. The rule's default threshold is 5000 ms; 2000 to 3000 ms is typical.
- **Not 0.** HikariCP treats `0` as "wait forever"; SpringTune reports that as an ERROR.
- **Not below 250 ms.** HikariCP enforces that minimum: a lower value is reset to 30 s, with only a log warning.
- **Map the exception.** `SQLTransientConnectionException` should become a 503 with `Retry-After`, not a 500 with a
  stack trace, so callers back off instead of retrying immediately.
- **Pair it with a query timeout.** A pool runs dry because connections are held too long; a
  `spring.transaction.default-timeout` (rule [ST-TX-001](../rules/ST-TX-001.md)) bounds how long one slow query can
  hold one.

## How the rule catches it

[ST-HIKARI-001](../rules/ST-HIKARI-001.md) reads the configuration the way Spring Boot resolves it:
`application*.yml` and `.properties`, profiles (yours, or each module's `spring.profiles.active`), placeholders
and relaxed binding. It reports a WARNING when `spring.datasource.hikari.connection-timeout` is unset or above
`max-ms` (default 5000), and an ERROR when it is `0`.

It only runs when the answer is knowable from the repository:

- HikariCP must be the pool: Spring Boot 2+ or an explicit HikariCP dependency, and not Druid or
  dynamic-datasource owning the `DataSource`, not `DataSourceAutoConfiguration` excluded.
- The local configuration must be complete. With `spring.config.import` from a Config Server, Nacos, a
  programmatic pool or a custom prefix, the module is *not evaluated*, and the report says why.

Tune the threshold or the severity in `springtune.yml`:

```yaml
rules:
  ST-HIKARI-001:
    severity: ERROR
    options:
      max-ms: 3000
```

Accepting the risk is explicit, with a reason that appears in the report and in code scanning:

```yaml
suppress:
  - rule: ST-HIKARI-001
    module: batch-importer
    reason: "Nightly batch, no user traffic; waiting is preferable to failing"
```

## See it fail

The [broken-config demo](../../examples/broken-config-ci/) is a small Spring Boot project with this exact mistake
(and an HTTP client without timeouts). Its workflow runs the SpringTune Action and turns the findings into a red
check with line-level annotations. To run the check on your own project, add the Maven plugin or Gradle plugin at
0.4.1 ([launch post](../launch/launch-post.md#try-it-on-your-repository-041)) and set `failOn` to `WARNING`.

## Related rules

- [ST-HIKARI-002](../rules/ST-HIKARI-002.md): leak detection disabled.
- [ST-TOMCAT-001](../rules/ST-TOMCAT-001.md): far more request threads than connections, which is what makes the
  queue in the table above possible.
- [ST-HIKARI-004](../rules/ST-HIKARI-004.md): all pools times all replicas must fit in the database's
  `max_connections` (needs your limits in `springtune.yml`).
