# ST-HIKARI-001: Connection acquire timeout too long

| | |
|---|---|
| **Severity** | WARNING (ERROR when 0 = infinite) |
| **Applies when** | Module uses JDBC with HikariCP (JDBC/JPA/jOOQ starter or `spring.datasource.*`, and `spring.datasource.type` unset or Hikari). |
| **Options** | `max-ms` (default `5000`). |

## What it detects

`spring.datasource.hikari.connection-timeout` is unset (HikariCP default 30000 ms), above `max-ms`, or `0` (HikariCP treats it as infinite).

## Why it matters

When the pool is exhausted, each request thread blocks for `connectionTimeout` before it fails. With 30 s, a short database slowdown turns into a fully blocked request thread pool, health checks time out, and upstream callers retry, which adds more load.

## Example

Flagged:

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 30000
```

Fixed:

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 3000   # shorter than the upstream request timeout
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HIKARI-001
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
