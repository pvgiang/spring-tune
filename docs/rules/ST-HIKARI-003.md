# ST-HIKARI-003: maxLifetime not shorter than database idle timeout

| | |
|---|---|
| **Severity** | ERROR |
| **Applies when** | Module uses HikariCP **and** its database declares `idleTimeout` in `springtune.yml`. Otherwise the rule is skipped and listed under *Not evaluated*. |
| **Options** | `margin-ms` (default `30000`). |

## What it detects

The effective `maxLifetime` (default 1800000 ms; values below 30000 ms are reset to the default by HikariCP; `0` = never retire) is not at least `margin-ms` shorter than the database/proxy idle timeout.

## Why it matters

The database, a proxy (PgBouncer, ProxySQL) or a firewall closes idle connections silently. HikariCP then hands out dead connections, and requests fail with `connection reset` / `communications link failure`.

## Example

Flagged:

```yaml
# springtune.yml: databases: [{name: main, maxConnections: 100, idleTimeout: 10m}]
# application.yml: max-lifetime left at default (30 min)
```

Fixed:

```yaml
spring.datasource.hikari.max-lifetime: 540000   # 9 min < 10 min idle timeout
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HIKARI-003
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
