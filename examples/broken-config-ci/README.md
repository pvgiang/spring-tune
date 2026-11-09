# broken-config-ci: see SpringTune fail a build

A small Spring Boot 4.1 service (`orders-service`) with four configuration mistakes that cause real outages. It is
standalone: its `pom.xml` uses Spring Boot's parent and SpringTune 0.4.1 from Maven Central, nothing from this
repository's build. The SpringTune check fails on it, on purpose. Each fix below is one small commit, and after all
four the check passes.

## Run it

In GitHub Actions: copy [`../github-workflow.yml`](../github-workflow.yml) to `.github/workflows/springtune.yml` in a
repository that contains this project at its root, and open a pull request. The job fails, and the four findings
appear as code-scanning alerts on the lines below. (While `pvgiang/spring-tune` is still private, the Action cannot
download the CLI in another repository's workflow: the release URLs return 404. Use the local run below until then.)

Locally (Java 17+, nothing is built or executed, nothing leaves your machine):

```bash
base=https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1
curl -fsSLO $base/springtune-check-cli-0.4.1-all.jar
echo "$(curl -fsSL $base/springtune-check-cli-0.4.1-all.jar.sha256)  springtune-check-cli-0.4.1-all.jar" | sha256sum -c -
java -jar springtune-check-cli-0.4.1-all.jar check .
```

Output (SpringTune 0.4.1, exit code 1):

```
SpringTune 0.4.1: 1 module(s), 5 finding(s) (0 error, 4 warning, 1 info), 0 suppressed

WARNING ST-HTTP-001  src/main/java/com/example/orders/InventoryClient.java:10:39
WARNING ST-HIKARI-002  src/main/resources/application.yml:1
INFO    ST-TX-001  src/main/resources/application.yml:1
WARNING ST-HIKARI-001  src/main/resources/application.yml:9:27
WARNING ST-TOMCAT-001  src/main/resources/application.yml:15:12

FAILED: 4 finding(s) at or above WARNING. Fix them or suppress with a reason in springtune.yml.
```

(Each finding also prints the reason, the fix and a link to the rule's page; trimmed here.)

The check fails on warnings because [`springtune.yml`](springtune.yml) sets `failOn: WARNING`. Without that file the
default is `ERROR`, and these four warnings are reported without failing the build.

## What fails and how to fix it

Apply the fixes in any order; each one removes exactly one finding.

### 1. ST-HIKARI-001: threads wait 30 s for a database connection

`connection-timeout: 30000`. When the pool runs dry, every request thread blocks for 30 s before it fails, so a short
database slowdown blocks the whole server, health checks included.
[Rule](../../docs/rules/ST-HIKARI-001.md)

```yaml
# src/main/resources/application.yml
spring.datasource.hikari.connection-timeout: 3000     # was 30000; shorter than the caller's timeout
```

### 2. ST-HIKARI-002: connection leaks go unnoticed

No `leak-detection-threshold`, so a connection that is borrowed and never returned shrinks the pool silently, until it
is exhausted. [Rule](../../docs/rules/ST-HIKARI-002.md)

```yaml
spring.datasource.hikari.leak-detection-threshold: 60000   # HikariCP logs the borrowing stack trace after 60 s
```

### 3. ST-TOMCAT-001: 400 request threads share 10 connections

`server.tomcat.threads.max: 400` with `maximum-pool-size: 10` (40:1, threshold 10:1). In a database slowdown, all
400 threads queue on the pool. [Rule](../../docs/rules/ST-TOMCAT-001.md)

```yaml
server.tomcat.threads.max: 100      # was 400; at most 10 threads per connection
```

### 4. ST-HTTP-001: an HTTP client with no timeout

`new RestTemplate()` has no connect or read timeout: if the inventory service hangs, request threads hang with it.
[Rule](../../docs/rules/ST-HTTP-001.md)

```java
// src/main/java/com/example/orders/InventoryClient.java
private final RestTemplate rest = new RestTemplate(timeouts());

private static SimpleClientHttpRequestFactory timeouts() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(3));
    return factory;
}
```

### Not failing: ST-TX-001 (INFO)

No transaction or query timeout is set, so a slow query holds its connection as long as the database lets it run.
It is INFO, below `failOn: WARNING`. To clear it: `spring.transaction.default-timeout: 30s` or
`spring.jdbc.template.query-timeout: 30s`. [Rule](../../docs/rules/ST-TX-001.md)

## Accepting a finding instead

```yaml
# springtune.yml
suppress:
  - rule: ST-TOMCAT-001
    reason: "Most endpoints never touch the database; reviewed 2026-11"   # required
```

The reason appears in the report and in code scanning.

## How this demo is kept working

SpringTune's CI (`.github/workflows/ci.yml`, job *Demo*) runs the repository's own Action on this project on every
push and weekly, and fails unless the check exits with code 1 and the SARIF report has exactly the five findings
above (the four warnings and the INFO), each with its file and line: a new, moved or missing finding fails it.

The runtime starter is on the classpath too: `mvn spring-boot:run`, then `curl localhost:8080/actuator/tuning` for
the live, read-only recommendations ([starter docs](../../docs/starter.md)).
