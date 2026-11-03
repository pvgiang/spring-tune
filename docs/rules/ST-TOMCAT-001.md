# ST-TOMCAT-001: Tomcat threads far above connection pool size

| | |
|---|---|
| **Severity** | WARNING when either value is set explicitly; INFO when both are library defaults (200 threads vs 10 connections) |
| **Applies when** | Module uses HikariCP through `spring.datasource.hikari.*` (see [technology detection](../check.md#which-rules-apply)) and embedded Tomcat (web/webmvc starter without Jetty/Undertow, or `server.tomcat.*` set), virtual threads are **not** enabled, and the local config is complete. `server.tomcat.max-threads` (Spring Boot 2.2 and earlier) is read when `server.tomcat.threads.max` is absent. |
| **Options** | `max-ratio` (default `10`). |

## What it detects

`server.tomcat.threads.max` (default 200) `>` `maximum-pool-size` (default 10) × `max-ratio`.

## Why it matters

When requests that need the database outnumber connections, the extra threads block in the pool. During a database slowdown, every Tomcat thread ends up waiting there, so endpoints that never touch the database, such as health checks, stop answering too.

## Example

Flagged:

```yaml
server.tomcat.threads.max: 400
spring.datasource.hikari.maximum-pool-size: 10
```

Fixed:

```yaml
server.tomcat.threads.max: 100
spring.datasource.hikari.maximum-pool-size: 20
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-TOMCAT-001
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
