# Runtime starter (`springtune-spring-boot-starter`)

Trust ladder levels 1–2 (*observe*, *advise*). **Nothing is ever changed**: no code path calls a setter on a
pool or executor, the endpoint has no write operation, and `springtune.mode=apply` is rejected at startup.

## What it watches

| Component | Knobs | Signals |
|---|---|---|
| Every `HikariDataSource` (also when wrapped) | `maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `leakDetectionThreshold` | active, idle, threads waiting (`HikariPoolMXBean`); mean acquire and hold time from the Micrometer timers `hikaricp.connections.acquire` / `.usage` |
| Every `ThreadPoolTaskExecutor` bean (including Boot's `applicationTaskExecutor`) | `corePoolSize`, `maxPoolSize`, `queueCapacity` | queued, active, pool size |

Components are discovered after startup. Each adapter activates only if its library is on the classpath.

## Recommendation rules

A rule fires only when the retained samples **cover the whole window** (and contain at least 3 samples).
Every recommendation carries its evidence and a confidence level.

| ID | Fires when | Action |
|---|---|---|
| `ST-REC-HIKARI-001` | Threads waited in ≥ `saturated-ratio` (80%) of samples over `window` (5 m) **and** the mean hold time ≤ `slow-usage` (500 ms) | INCREASE by `step` (25%, at least +1), capped by the budget. HOLD when at or above the cap. |
| `ST-REC-HIKARI-002` | Same waiting, but the mean hold time > `slow-usage`, or hold-time metrics are missing | HOLD: "do not raise the pool", points at slow queries/transactions (HIGH confidence; LOW without metrics) |
| `ST-REC-HIKARI-003` | Over `low-utilization-window` (24 h) no thread ever waited and peak active < `low-utilization` (20%) of the pool | DECREASE to max(2, 2 × peak) |
| `ST-REC-EXEC-001` | Queued tasks with all threads busy in ≥ `backlog-ratio` (80%) of samples | Unbounded queue: INCREASE `corePoolSize` (max size has no effect). Bounded queue at max: INCREASE `maxPoolSize`. |

The **budget cap** uses the same `ConnectionBudget` formula as CI rule ST-HIKARI-004:
`floor(database-max-connections × safety-factor / max-replicas)`.

## Properties

| Property | Default | |
|---|---|---|
| `springtune.enabled` | `true` | `false` removes every SpringTune bean |
| `springtune.mode` | `advise` | `observe` (no recommendations) or `advise` |
| `springtune.sampling.interval` | `10s` | |
| `springtune.budget.database-max-connections` / `max-replicas` | unset | Must be set together; startup fails otherwise |
| `springtune.budget.safety-factor` | `0.8` | |
| `springtune.hikari.window` / `saturated-ratio` / `slow-usage` / `step` | `5m` / `0.8` / `500ms` / `0.25` | |
| `springtune.hikari.low-utilization-window` / `low-utilization` | `24h` / `0.2` | |
| `springtune.executor.window` / `backlog-ratio` / `step` | `5m` / `0.8` / `0.25` | |

The gauge `springtune.recommendations` reports the number of current recommendations.

## Endpoint

Expose with `management.endpoints.web.exposure.include=tuning`, then `GET /actuator/tuning`:

```json
{
  "mode": "ADVISE", "applySupported": false,
  "budget": { "databaseMaxConnections": 100, "maxReplicas": 6, "safetyFactor": 0.8, "perInstanceCap": 13 },
  "components": [ { "id": "hikari:orders-pool", "samples": 25, "knobs": [ ... ], "signals": { "waitingThreads": 39, ... } } ],
  "recommendations": [ {
      "ruleId": "ST-REC-HIKARI-002", "knobId": "hikari.orders-pool.maximumPoolSize", "action": "HOLD",
      "reason": "Do not raise the pool. Threads wait for connections because each connection is held for 900 ms ...",
      "evidence": { "samplesWithWaitingThreads": "100%", "meanConnectionHoldTime": "900 ms", ... },
      "confidence": "HIGH", "relatedRuleIds": ["ST-TX-001", "ST-HIKARI-002"] } ]
}
```

## How it was verified

| Check | Test |
|---|---|
| Rules on synthetic signals (raise, cap, above cap, slow holds, missing metrics, lower, coverage) | `AdvisorTest` |
| Conditions: disabled, no Hikari on the classpath, endpoint needs exposure, `apply` rejected, half-configured budget rejected | `SpringTuneAutoConfigurationTest` |
| **Real HikariCP + H2 + Micrometer**, 8 threads on 2 connections: 15 ms holds → INCREASE 2→3 (cap 13); budget cap 2 → HOLD; 700 ms holds → "do not raise" | `HikariContentionIntegrationTest` |
| Real Boot app: `/actuator/tuning` content, POST rejected | `BrokenConfigApplicationTest` |
| Manual: sample app with 60 concurrent HTTP clients, 300 ms holds → HOLD "above budget cap 13 (current 20)"; 900 ms holds → HOLD "do not raise" | see delivery brief §6 |
