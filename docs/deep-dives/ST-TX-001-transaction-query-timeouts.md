# The query that holds a connection until the database gives up: transaction and query timeouts

*Deep dive #3 · rule [ST-TX-001](../rules/ST-TX-001.md) · SpringTune 0.4.0 · numbers traced in
[docs/launch/claims.md](../launch/claims.md)*

Spring Boot sets no transaction timeout and no query timeout. A query that runs for ten minutes keeps its pooled
connection for ten minutes, and the application has no say in it: only the database decides when the query ends.
A pool of 10 connections needs only 10 such queries to stop serving everyone else.

## How it goes wrong

The story below is a composite of a common incident pattern, not a report about any real company. The numbers are
illustrative.

An order service runs on Spring Boot's defaults: 200 Tomcat threads, a HikariCP pool of 10 connections, a
30-second `connectionTimeout`, and no transaction or query timeout. Its normal queries take a few milliseconds.
One admin screen exports a month of orders; on the data it was written for, the export query took 2 seconds.

The table has grown. A new filter on that screen, released in the afternoon, makes the database scan the whole
table instead of using an index. The export now takes about 4 minutes.

| Time | What happens |
|---|---|
| 0 s | Someone clicks *Export*. One connection is now busy for 4 minutes. |
| ~1 min | The page looks stuck, so they click again, and a colleague tries too. 4 connections are held by exports. |
| ~3 min | 10 export requests are running. All 10 connections are held; every other request waits for a connection. |
| 3 min onward | Each waiting request blocks a Tomcat thread for up to 30 s and then fails with `SQLTransientConnectionException`. Checkout, order history, and the health check if it touches the database, all fail. |
| ~7 min | The first exports finish, but the retries that queued behind them start new 4-minute scans. |
| until someone acts | Someone kills the queries on the database, or restarts the service, which drops the connections. The queries may keep running on the database until it notices. |

Nothing here was slow on the application side. The application simply had no limit on how long it would let a
single statement keep a connection, so the database's behavior became the service's availability.

With `spring.transaction.default-timeout: 30s`, the same afternoon looks different: each export fails after
30 seconds with a timeout error that names the problem, the driver asks the database to cancel the statement, and
the connection goes back to the pool. The export screen is broken; the rest of the service is not.

## How often it shows up

In our [corpus of 20 public Spring Boot repositories](../corpus/2026-11-corpus-report.md) (re-run with the released
0.4.0 CLI, [identical results](../corpus/2026-11-09-0.4.0-rerun.md)), ST-TX-001 reported **70 modules in 16
repositories**, the largest group of findings in the run. The author's review found **1 false positive**:
`demo-codegen` in `xkcoding/spring-boot-demo` opens its own JDBC connections for code generation, with no
Spring-managed DataSource, so a Spring transaction timeout would not apply to it.

- **42 of the 70** are in the two tutorial collections (`ityouknow/spring-boot-examples` 25,
  `xkcoding/spring-boot-demo` 17). They are correct findings, but tutorials.
- **The other 28** are in 14 repositories: FTGO 7, Apache DolphinScheduler 5, mall 4, JeecgBoot 2, and 1 each in
  ruoyi-vue-pro, Apache ShenYu, eladmin, the RealWorld example app, the JHipster sample app, litemall, Spring
  PetClinic REST, Spring PetClinic, ThingsBoard and RuoYi.
- **The pool does not matter to this rule.** 54 of the 70 modules use HikariCP (43 of them were also flagged by
  [ST-HIKARI-001](ST-HIKARI-001-connection-timeout.md) for its 30-second wait; 11 build their pool in code). In the
  other 16, HikariCP was not detected: Druid-managed datasources, for example, or Spring Boot 1.x apps on
  tomcat-jdbc.
- **43 of the 45** modules flagged by ST-HIKARI-001 have no transaction or query timeout either: the combination
  from the story above, a slow query holding a connection and every other request waiting up to 30 s for one.

One real example: [Spring PetClinic](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/src/main/resources/application.properties)
at the pinned commit uses JPA, and its `application.properties` sets no transaction or query timeout. For a sample
application that is a reasonable choice; copied into a service, it is the default described above.

The rule needs the module's configuration. Where the configuration lives outside the repository (Spring Cloud
Config Server, Nacos, or a config name or location set in code), the check reports *not evaluated* for every rule
that reports a missing property; that happened for 45 modules in this corpus. ST-TX-001 counts nothing there.

## The fix

Set a default for every Spring-managed transaction, and a query timeout for statements that run outside one:

```yaml
spring:
  transaction:
    default-timeout: 30s            # every @Transactional without its own timeout
  jdbc:
    template:
      query-timeout: 30s            # JdbcTemplate statements, also outside transactions
  jpa:
    properties:
      jakarta.persistence.query.timeout: 30000   # milliseconds; JPA queries (javax.* on older Hibernate)
```

How to pick the value: shorter than the timeout of whoever calls you (the HTTP request timeout, the gateway's read
timeout), and long enough for your slowest legitimate transaction on a normal day. Work that really takes longer,
such as a nightly batch, gets its own, explicit limit:

```java
@Transactional(timeout = 600)   // seconds; the nightly reconciliation, and nothing else, may run 10 minutes
public void reconcile() { ... }
```

What happens at the timeout: within a transaction, Spring passes the remaining time to JDBC statements (through
`JdbcTemplate`, or to JPA queries as a query timeout), and the driver asks the database to cancel the statement.
Your code gets an exception (`QueryTimeoutException` or a `TransactionTimedOutException`, depending on where the
time ran out). Map it to a 503 or a clear error message, and do not retry it immediately: a query that timed out once
will usually time out again.

A server-side limit is a useful second line: PostgreSQL's `statement_timeout` or MySQL's `max_execution_time`,
set for the application's database role. It also stops queries the application never sees, such as ones started by
a script with the same credentials.

## How the rule catches it

[ST-TX-001](../rules/ST-TX-001.md) runs for every module that uses JDBC with any pool and whose configuration is in
the repository. It reports one INFO finding when none of these properties is set in the resolved configuration
(all profiles the module activates):

- `spring.transaction.default-timeout`
- `spring.jdbc.template.query-timeout`
- `spring.jpa.properties.jakarta.persistence.query.timeout` (or `javax.`)

and no `@Transactional(timeout = ...)` appears in the module's own Java sources. (An annotation in a shared,
in-repository library module is not looked at for this rule; if that is where your timeouts live, suppress the
finding with that reason.)

It is INFO, not WARNING: a missing timeout is a risk, not a defect, and some services rely on a database-side
limit. With the default `--fail-on ERROR` (and with `failOn: WARNING`), it is reported but does not fail the
build.

What it deliberately does not catch, or cannot see:

- **One annotation is enough to silence it.** A single `@Transactional(timeout = ...)` anywhere in the module
  counts, even if every other transaction has no limit. The rule checks that the team has thought about timeouts,
  not that every path is covered.
- **Timeouts set in code** (`JdbcTemplate.setQueryTimeout`, a query hint per query, a `TransactionManager` built by
  hand) are not read. Such a module gets a finding it may not need; suppress it with the reason.
- **Database-side limits** (`statement_timeout` on the role, or in the JDBC URL) are not read either. Same answer:
  suppress with the reason.
- **Statements outside Spring's transactions and outside `JdbcTemplate`**, such as plain JDBC on a connection you
  borrowed yourself, are not bounded by any of these properties. A clean ST-TX-001 does not prove that every query
  has a limit.
- **The value is not judged.** `default-timeout: 1h` passes.

To accept the finding for a module, suppress it with a reason:

```yaml
# springtune.yml
suppress:
  - rule: ST-TX-001
    module: reporting-service
    reason: "statement_timeout = 20s is set on the app_reporting role (see db/roles.sql)"
```

## See it fail

The [broken-config demo](../../examples/broken-config-ci/) reports ST-TX-001 at INFO next to its four warnings,
with the same fix. To make INFO findings fail your own build, run the check with `--fail-on INFO` (or `failOn: INFO`
in `springtune.yml`):

```bash
java -jar springtune-check-cli-0.4.1-all.jar check . --fail-on INFO
```

The jar, its checksum, and the Maven, Gradle and GitHub Action setups are in the
[launch post](../launch/launch-post.md#try-it-on-your-repository-041).

## Related rules

- [ST-HIKARI-001](../rules/ST-HIKARI-001.md): how long a request waits for a connection once the pool is empty
  ([deep dive #1](ST-HIKARI-001-connection-timeout.md)).
- [ST-HIKARI-002](../rules/ST-HIKARI-002.md): leak detection, for connections that are held without a query running.
- [ST-TOMCAT-001](../rules/ST-TOMCAT-001.md): request threads versus pool size, which decides how many requests
  can queue behind a held connection.
