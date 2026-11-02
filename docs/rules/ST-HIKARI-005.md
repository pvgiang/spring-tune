# ST-HIKARI-005: Oversized connection pool

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | Module uses HikariCP and sets `maximum-pool-size` explicitly. |
| **Options** | `max` (default `50`). |

## What it detects

`maximum-pool-size` per instance above `max`.

## Why it matters

A database runs only about `cores × 2` queries in parallel efficiently. Extra connections queue inside the database instead of the pool, which raises latency for every service, and they multiply by the replica count against `max_connections`.

## Example

Flagged:

```yaml
spring.datasource.hikari.maximum-pool-size: 100
```

Fixed:

```yaml
spring.datasource.hikari.maximum-pool-size: 20
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HIKARI-005
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
