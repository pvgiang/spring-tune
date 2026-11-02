# ST-HIKARI-002: Connection leak detection disabled

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | Module uses HikariCP. |
| **Options** | none. |

## What it detects

`spring.datasource.hikari.leak-detection-threshold` is unset or `0`, below 2000 ms, or above `maxLifetime`. HikariCP silently disables leak detection in the last two cases.

## Why it matters

A connection that is borrowed and never returned shrinks the pool for good. Without leak detection, the first symptom is pool exhaustion in production, with no stack trace pointing at the leaking code.

## Example

Flagged:

```yaml
# nothing set
```

Fixed:

```yaml
spring.datasource.hikari.leak-detection-threshold: 60000
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HIKARI-002
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
