# ST-HIKARI-004: Connection budget exceeded

| | |
|---|---|
| **Severity** | ERROR |
| **Applies when** | Modules using HikariCP that map to a database in `springtune.yml` (explicitly via `services[].database`, or implicitly when exactly one database is declared) **and** whose max replicas are known. If any member's pool size is a runtime placeholder or its replicas are unknown, the database is skipped and listed under *Not evaluated*. |
| **Options** | none (set `safetyFactor` per database). |

## What it detects

`Σ maximumPoolSize × maxReplicas` over all services of a database `> floor(maxConnections × safetyFactor)` (default safety factor 0.8). Replicas come from `services[].maxReplicas`, else `services[].deployment` files (HPA `maxReplicas` > Deployment `replicas`; Helm `autoscaling.maxReplicas` when enabled > `replicaCount`), else a single auto-discovered manifest in the module directory.

## Why it matters

At peak scale, or during a rolling update, pools try to open more connections than the database accepts. New pods fail readiness and requests fail with `too many connections`, typically when traffic is highest. The same formula (`ConnectionBudget` in `springtune-model`) caps the runtime starter's recommendations.

## Example

Flagged:

```yaml
databases:
  - name: main
    maxConnections: 100
services:
  - path: .
    maxReplicas: 10      # 10 × default pool 10 = 100 > 80
```

Fixed:

```yaml
spring.datasource.hikari.maximum-pool-size: 8   # 8 × 10 = 80 ≤ 80
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HIKARI-004
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
