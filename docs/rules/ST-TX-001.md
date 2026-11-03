# ST-TX-001: No transaction or query timeout

| | |
|---|---|
| **Severity** | INFO |
| **Applies when** | Module uses JDBC with any pool, and the local config is complete. |
| **Options** | none. |

## What it detects

None of `spring.transaction.default-timeout`, `spring.jdbc.template.query-timeout`, `spring.jpa.properties.jakarta.persistence.query.timeout` (or `javax.`) is set, and no `@Transactional(timeout = ...)` exists.

## Why it matters

A slow query keeps its pooled connection for as long as the database lets it run. A handful of them drains the pool, and every other request then waits for `connectionTimeout`.

## Example

Flagged:

```yaml
# nothing set
```

Fixed:

```yaml
spring.transaction.default-timeout: 30s
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-TX-001
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
