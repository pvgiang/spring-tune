# Runtime starter (`springtune-spring-boot-starter`)

Trust ladder levels 1–3:

| Level | `springtune.mode` | What happens |
|---|---|---|
| 1 | `observe` | Knobs and signals only. Nothing is changed. |
| 2 | `advise` (default) | Also explained recommendations and an **apply plan** for each (what to commit). Nothing is changed. |
| 3 | `apply` (**experimental**, opt-in) | An authenticated operator can apply **one current recommendation at a time** through `POST /actuator/tuning`. Each change is bounded, verified against the knob's own signals, reverted automatically when it regresses or cannot be verified, audited, and **ephemeral** (lost on restart). |

There is no level 4: SpringTune never changes anything on its own initiative. In `observe` and `advise` mode no code
path calls a setter on a pool or executor and the endpoint has no write operation (`POST` returns 404/405).

## What it watches

| Component | Knobs | Signals |
|---|---|---|
| Every `HikariDataSource` (also when wrapped) | `maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `leakDetectionThreshold` | active, idle, threads waiting (`HikariPoolMXBean`); mean acquire and hold time from the Micrometer timers `hikaricp.connections.acquire` / `.usage`; timeouts from `hikaricp.connections.timeout` (apply mode) |
| Every `ThreadPoolTaskExecutor` bean (including Boot's `applicationTaskExecutor`) | `corePoolSize`, `maxPoolSize`, `queueCapacity` | queued, active, pool size; completed and rejected tasks (apply mode) |

Components are discovered after startup. Each adapter activates only if its library is on the classpath.

## Recommendation rules

A rule fires only when the retained samples **cover the whole window** (and contain at least 3 samples).
Every recommendation carries its evidence and a confidence level.

| ID | Fires when | Action |
|---|---|---|
| `ST-REC-HIKARI-001` | Threads waited in ≥ `saturated-ratio` (80%) of samples over `window` (5 m) **and** the mean hold time ≤ `slow-usage` (500 ms) | INCREASE by `step` (25%, at least +1), capped by the budget. HOLD when at or above the cap. |
| `ST-REC-HIKARI-002` | Same waiting, but the mean hold time > `slow-usage`, or hold-time metrics are missing | HOLD: "do not raise the pool", points at slow queries/transactions (HIGH confidence; LOW without metrics) |
| `ST-REC-HIKARI-004` | Same waiting, but the pool held **fewer connections than its maximum** while threads waited in ≥ 50% of the window's samples, or acquire timeouts occurred while it did | HOLD: "do not raise the pool": the database refuses or fails new connections (`max_connections`, a role `CONNECTION LIMIT`, network, authentication), so a larger pool would only add connection attempts to a failing database. Checked before 001/002 (HIGH confidence). |
| `ST-REC-HIKARI-003` | Over `low-utilization-window` (24 h) no thread ever waited and peak active < `low-utilization` (20%) of the pool | DECREASE to max(2, 2 × peak) |
| `ST-REC-EXEC-001` | Queued tasks with all threads busy in ≥ `backlog-ratio` (80%) of samples | Unbounded queue: INCREASE `corePoolSize` (max size has no effect). Bounded queue at max: INCREASE `maxPoolSize`. |

The **budget cap** uses the same `ConnectionBudget` formula as CI rule ST-HIKARI-004:
`floor(database-max-connections × safety-factor / max-replicas)`.

## Apply plans (advise and apply mode)

Every INCREASE/DECREASE recommendation gets an entry in `applyPlans`:

```json
{ "ruleId": "ST-REC-HIKARI-001", "knobId": "hikari.orders-pool.maximumPoolSize",
  "target": "DataSource bean 'dataSource' (Hikari pool 'orders-pool')", "from": 10, "to": 13,
  "commit": [ "spring.datasource.hikari.maximum-pool-size=13", "spring.datasource.hikari.minimum-idle=13" ],
  "budget": [ "per-instance cap = floor(database-max-connections x safety-factor / max-replicas) = floor(100 x 0.8 / 6) = 13",
              "13 <= cap 13: within budget", "fleet at max replicas: 13 x 6 = 78 of 80 usable connections (this service only)" ],
  "sideEffects": [ "minimumIdle equals maximumPoolSize (10, a fixed-size pool, Hikari's default): it moves to 13 as well, ...",
                   "Hikari opens the additional connections on demand, as threads wait for one." ],
  "persistence": "ephemeral: a change applied at runtime (springtune.mode=apply) is lost on restart; commit the lines above to keep it." }
```

- Property names are given only where they are certain: Boot's auto-configured `dataSource` bean
  (`spring.datasource.hikari.*`) and `applicationTaskExecutor` (`spring.task.execution.pool.*`). For any other bean
  the plan names the bean and pool and says the property prefix is application-specific.
- `minimumIdle` moves with `maximumPoolSize` when they are equal (a fixed pool, Hikari's default); otherwise raising
  the maximum would silently make the pool dynamic.
- A smaller Hikari maximum is applied **lazily**: connections above it are retired as they idle out (only while
  `minimumIdle < maximumPoolSize`) or reach `maxLifetime`, not closed at once.
- Executor: `corePoolSize` may not exceed `maxPoolSize`; when it would, `maxPoolSize` moves first.

## Apply mode (experimental)

```yaml
springtune:
  mode: apply
  budget:                      # required to raise a Hikari pool
    database-max-connections: 100
    max-replicas: 6
  apply:
    required-role: TUNER       # optional; any authenticated caller otherwise
management:
  endpoints.web.exposure.include: tuning
```

**Startup fails in apply mode if Spring Security is not on the classpath.** There is no "allow anonymous" switch: a
call without a principal, or with Spring Security's anonymous authentication (`anonymousUser`), is refused (401) even
if your security configuration permits it. You must secure the
endpoint yourself, for example:

```java
@Bean
@Order(1)
SecurityFilterChain tuning(HttpSecurity http) throws Exception {
    return http.securityMatcher("/actuator/tuning")            // or EndpointRequest.to("tuning")
            .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("TUNER"))
            .httpBasic(Customizer.withDefaults())               // or your SSO / mTLS / resource server
            .csrf(csrf -> csrf.ignoringRequestMatchers("/actuator/tuning"))   // API clients, no browser session
            .build();
}
```

On WebFlux, secure it with a `SecurityWebFilterChain` (`ServerHttpSecurity`) the same way; an `anonymous()`
principal is refused there too.

(`EndpointRequest` lives in `org.springframework.boot.actuate.autoconfigure.security.servlet` on Boot 3 and in
`org.springframework.boot.security.autoconfigure.actuate.web.servlet` on Boot 4.)

### Requests

`POST /actuator/tuning` with `Content-Type: application/json`:

| `action` | Fields | Effect |
|---|---|---|
| `apply` | `ruleId`, `knobId`, `expectedCurrent`, `reason` (all required); `expectedTarget` (optional, recommended) | Applies the `recommendedValue` of the matching **current** recommendation (re-evaluated at request time). No other value can be applied, and HOLD has nothing to apply. With `expectedTarget`, the apply is refused if the fresh recommendation now proposes a different value than the one you reviewed. |
| `revert` | `knobId`, optional `reason` | Writes back the change in flight, or the last confirmed change of that knob, if the live value is still the applied one. |
| `freeze` | optional `reason` | Reverts the change in flight and refuses every further apply **until restart** (runtime kill switch). |

```bash
curl -u ops:… -H 'Content-Type: application/json' -X POST https://host/actuator/tuning \
  -d '{"action":"apply","ruleId":"ST-REC-HIKARI-001","knobId":"hikari.orders-pool.maximumPoolSize","expectedCurrent":10,"expectedTarget":13,"reason":"INC-123 pool saturated"}'
```

The response is `{status, code, message, change}` with HTTP status **200** (done), **400** (invalid request or
outside the guardrails), **401** (no principal), **403** (missing `required-role`) or **409** (conflicts with the
current state). Every request, including refusals, is audited with the caller's principal name. Unauthenticated refusals (401) are logged on `springtune.audit` and published as events, but not kept in the in-memory audit, so anonymous traffic cannot push genuine entries out of it.

### Guardrails

| Guardrail | Refusal code (HTTP) |
|---|---|
| Write operation exists only with `mode=apply`; web only (no JMX setter) | n/a (`POST` is 404/405 otherwise); `MODE_NOT_APPLY` (400) |
| Authenticated caller; optional `required-role` | `NOT_AUTHENTICATED` (401), `ROLE_REQUIRED` (403) |
| `springtune.apply.enabled=false` (static kill switch) | `DISABLED` (409) |
| Frozen (`freeze` action, or a failed revert) until restart | `FROZEN` (409) |
| Exactly one change in flight per process | `CHANGE_IN_FLIGHT` (409) |
| Only writable knobs: Hikari `maximumPoolSize`; executor `corePoolSize` (unbounded queue) or `maxPoolSize` (bounded queue) | `UNKNOWN_KNOB` (400) |
| Knob cooling down: `cooldown` (15 m) after a confirmed/superseded change, `cooldown-after-revert` (60 m) after a revert | `COOLDOWN` (409) |
| A current, matching (`ruleId` + `knobId`) recommendation that is not HOLD | `NO_CURRENT_RECOMMENDATION`, `RECOMMENDATION_IS_HOLD` (409) |
| Compare-and-set: `expectedCurrent` and the recommendation's `currentValue` must equal the live value | `STALE_EXPECTED_CURRENT` (409) |
| Optional `expectedTarget` must equal the fresh recommendation's value | `STALE_EXPECTED_TARGET` (409) |
| Raising a Hikari pool needs `springtune.budget.*` and stays ≤ the per-instance cap | `NO_BUDGET`, `ABOVE_BUDGET_CAP` (400) |
| Raising a Hikari pool: `database-max-connections` must not exceed what the **server** accepts from application roles (read once through the pool: PostgreSQL `max_connections − superuser_reserved_connections − reserved_connections`, MySQL/MariaDB `@@max_connections`; other databases are not checked and the apply plan says so) | `BUDGET_EXCEEDS_SERVER` (409) |
| Step: `abs(new − current) ≤ max(1, ceil(current × max-step))` | `STEP_LIMIT` (400) |
| Growth: `new` within `[startup / max-growth-factor, startup × max-growth-factor]` | `GROWTH_LIMIT` (400) |
| Counters cover the `baseline` window before the change | `BASELINE_NOT_COVERED` (409) |

Only the knob writers (`HikariPoolWriter`, `ExecutorPoolWriter`) call size setters; `WriterIsolationTest` fails the
build if any other class does. Write order keeps components valid at every step: Hikari grows `maximumPoolSize` then
`minimumIdle` and shrinks `minimumIdle` first; executors grow `maxPoolSize` then `corePoolSize` and shrink
`corePoolSize` first.

### Verification and automatic revert

After a change at `t0`, the sampler tick compares two windows of the **knob's own** component:
**B** = counter deltas over `[t0 − baseline, t0]`, **V** = deltas over `[t0 + warmup, now]`.

| Rule | Regression when | Evaluated when |
|---|---|---|
| R1 (Hikari) | timeouts per acquire attempt up by > 0.1 percentage points with ≥ 3 timeouts in V; checked on **every tick** (fast fail) | regressed, or ≥ 100 attempts in B and V |
| R2 (Hikari) | mean connection hold time in V > 1.5 × B + 5 ms ("more connections made each one slower") | ≥ 100 usages in B and V |
| R3 (Hikari) | mean acquire time in V > 1.5 × B + 5 ms | ≥ 100 acquires in B and V |
| R6 (executor) | rejections per second in V higher than in B, with ≥ 1 rejection in V (counted by a delegating `RejectedExecutionHandler` installed only in apply mode; the original policy still runs) | regressed, or ≥ 100 completed tasks in B and V |

If throughput (acquire attempts per second, or completed tasks per second) differs by more than 2× between B and V,
or no rule is evaluated, the verdict is **INCONCLUSIVE**. HTTP mean latency and 5xx counts are recorded as evidence
and **never** trigger a revert, so an unrelated downstream incident cannot revert (or shrink) a pool mid-spike.

```
IDLE --apply--> VERIFYING --OK------------------------> CONFIRMED  --cooldown-->            IDLE
                          --REGRESSED / INCONCLUSIVE--> REVERTED   --cooldown-after-revert--> IDLE
                          --INCONCLUSIVE + keep-------> CONFIRMED (outcome KEPT_INCONCLUSIVE)
                          --live value changed--------> SUPERSEDED (left alone) --cooldown--> IDLE
                          --revert write failed-------> REVERT_FAILED (frozen until restart, WARN)
```

A revert writes the before value back **only if the live value still equals the applied value**; otherwise the
change is SUPERSEDED and left alone. `on-inconclusive=revert` (default) means low-traffic services usually cannot
keep a change: every change must be verified, and the reason is shown. `keep` is an explicit opt-in.

### Snapshot and audit

In apply mode `GET /actuator/tuning` has `"applySupported": true` and an `apply` block: `enabled`, `frozen`,
`requiredRole`, the effective `guardrails`, every writable knob with its **configured** (startup) and **effective**
(live) value, the `min`/`max`/`maxStep` the next change may use and its state (with `cooldownUntil`), and the
`audit` list (newest first, `audit-size` entries): `who`, `why`, `ruleId`, `knobId`, `before`, `after`, `outcome`,
`verdict`, `reasons` and `evidence` (B → V for each signal).

Every audit entry is also logged on the logger **`springtune.audit`** (WARN for `REVERT_FAILED`) and published as a
`SpringTuneChangeEvent` (listen to it to forward changes). The counter **`springtune.changes{outcome}`** counts final
outcomes (`confirmed`, `reverted`, `kept_inconclusive`, `superseded`, `revert_failed`, `apply_failed`, `refused`,
`frozen`).

### Caveats

- **Ephemeral.** Applied values live only in the running process. A restart (or a new pod) starts from the
  configuration again; that is intended. Commit the plan's `commit` lines to keep a change. The snapshot shows
  `configuredValue` vs `effectiveValue` so drift is visible.
- **Per instance.** Apply acts on the instance that receives the request; behind a load balancer you cannot choose
  which one. Other replicas are unaffected, and nothing coordinates changes across replicas. The budget cap assumes
  every replica could be at the same size, so raising one instance never exceeds it.
- **Count surge pods in `max-replicas`.** The cap is only as good as `max-replicas`. A rolling update's `maxSurge`
  pods and an HPA's maximum run at the same pool size as everyone else: set `max-replicas` to the **largest number
  of pods that can run at once** (HPA max + surge), not the steady-state count. The load suite shows what happens
  otherwise: 4 pods with `max-replicas=3` reached 19 sessions against a usable budget of 16 and the server refused
  connections (`docs/loadtests.md`).
- **Other clients of the database.** `database-max-connections` is what *this service* may use. Other services,
  admin sessions, migrations and monitoring must fit in the headroom the safety factor leaves
  (`max − floor(max × safety-factor)`), or be subtracted from `database-max-connections`.
- **Server limit check.** In apply mode with a budget, SpringTune reads the server's connection limit once, with one
  connection borrowed from the pool like any other request, on a short-lived thread (the sampler never waits for
  it). Until that read succeeds the check is skipped and the apply plan says "server limit not checked yet". It is
  not re-read after a database restart with a different `max_connections`; restart the application then.
- **Failing database.** When threads wait but the pool cannot open the connections it has room for (connections
  refused or failing), the recommendation is `ST-REC-HIKARI-004` HOLD, so apply is refused
  (`RECOMMENDATION_IS_HOLD`). A change already in flight is still judged by R1 (timeouts) and reverted.
- **A revert of a fixed-size pool does not close the extra connection at once.** With `minimumIdle` =
  `maximumPoolSize`, HikariCP never retires idle connections, so a connection opened while the pool was larger
  stays open until `maxLifetime`: after a 4 → 5 → 4 revert the database still sees 5 sessions from that instance for
  up to `maxLifetime` (30 min by default). The budget cap already assumes every instance at the cap, so this never
  exceeds it.
- **Per pool.** The per-instance cap applies to each Hikari pool separately (as in ST-HIKARI-004). If one instance has
  several pools on the **same** database, size `springtune.budget.*` for that (e.g. count each pool as a replica).
- **Cumulative metrics only.** Verification and hold-time means use differences of cumulative Micrometer values.
  SpringTune reads from the first non-`StepMeterRegistry` registry (e.g. Prometheus, `SimpleMeterRegistry`) and treats
  hold-time metrics as unavailable when only step registries exist (Datadog, New Relic, ...). Push registries that do
  not extend `StepMeterRegistry` but report deltas (OTLP with delta temporality, `SimpleMeterRegistry` in `STEP`
  mode) cannot be detected and give wrong results.
- The step limit means a large DECREASE (e.g. ST-REC-HIKARI-003 from 20 to 2) cannot be applied at runtime; change the
  configuration instead.
- Verification compares windows, so a workload whose traffic shifts by more than 2× during verification is
  INCONCLUSIVE (and reverted by default).

## Properties

| Property | Default | |
|---|---|---|
| `springtune.enabled` | `true` | `false` removes every SpringTune bean |
| `springtune.mode` | `advise` | `observe`, `advise` or `apply` (experimental; requires Spring Security) |
| `springtune.sampling.interval` | `10s` | Also drives apply-mode verification |
| `springtune.budget.database-max-connections` / `max-replicas` | unset | Must be set together; startup fails otherwise. Required to raise a Hikari pool in apply mode |
| `springtune.budget.safety-factor` | `0.8` | |
| `springtune.hikari.window` / `saturated-ratio` / `slow-usage` / `step` | `5m` / `0.8` / `500ms` / `0.25` | |
| `springtune.hikari.low-utilization-window` / `low-utilization` | `24h` / `0.2` | |
| `springtune.executor.window` / `backlog-ratio` / `step` | `5m` / `0.8` / `0.25` | |
| `springtune.apply.enabled` | `true` | `false`: static kill switch (revert and freeze still work) |
| `springtune.apply.required-role` | unset | Role checked with the endpoint's security context (`isUserInRole`) |
| `springtune.apply.max-step` | `0.5` | `abs(new − current) ≤ max(1, ceil(current × max-step))`, in (0, 1] |
| `springtune.apply.max-growth-factor` | `2.0` | `new` within `[startup / f, startup × f]` |
| `springtune.apply.baseline` | `5m` | B window before the change |
| `springtune.apply.warmup` | `30s` | Ignored after the change |
| `springtune.apply.verification-window` | `5m` | V window after the warmup |
| `springtune.apply.on-inconclusive` | `revert` | `revert` or `keep` |
| `springtune.apply.cooldown` | `15m` | Knob lock after a confirmed or superseded change |
| `springtune.apply.cooldown-after-revert` | `60m` | Knob lock after a revert |
| `springtune.apply.audit-size` | `100` | Audit entries kept in memory |

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
      "confidence": "HIGH", "relatedRuleIds": ["ST-TX-001", "ST-HIKARI-002"] } ],
  "applyPlans": [ ],
  "apply": null
}
```

## How it was verified

| Check | Test |
|---|---|
| Rules on synthetic signals (raise, cap, above cap, slow holds, missing metrics, lower, coverage) | `AdvisorTest` |
| Conditions: disabled, no Hikari on the classpath, endpoint needs exposure, invalid mode rejected, half-configured budget rejected, **apply without Spring Security fails startup** (`FilteredClassLoader`), write extension only in apply mode, step registries not read as cumulative | `SpringTuneAutoConfigurationTest` |
| Every guard refusal (mode, disabled, frozen, in flight, unknown knob, cooldown, no/HOLD recommendation, compare-and-set, no budget, above cap, step, growth, baseline) and caller checks | `ApplyGuardTest` |
| Verification rules R1–R3, R6, minimums, 2× throughput → INCONCLUSIVE, HTTP evidence only | `VerificationRuleTest` |
| State machine with `MutableClock` and fake writers: confirm + cooldown, regression revert + 60 m lock, R1 fast fail, inconclusive revert/keep, superseded, failed revert freezes, freeze, one change in flight, audited refusals, manual revert | `ChangeControllerTest` |
| Writers on a real `ThreadPoolTaskExecutor` (unbounded core and bounded max, both directions, across the other bound) and a real Hikari pool; rejection counter keeps the original policy | `KnobWriterTest` |
| Only `*Writer` classes call size setters | `WriterIsolationTest` |
| **Real HikariCP + H2 + Micrometer**, 8 threads on 2 connections: (a) 15 ms holds → apply 2→3, `minimumIdle` 2→3, ≤ 3 connections and 3 used, CONFIRMED, audit complete, `springtune.changes{outcome=confirmed}`; (b) a database that slows down above 2 concurrent sessions (15 → 35 ms) → R2 → REVERTED to 2/2 | `HikariApplyIntegrationTest` |
| Real Boot web app with actuator + Spring Security: unauthenticated 401, wrong role 403, right role 200 with `who` recorded, invalid body 400 | `ApplySecurityWebTest` |
| Misconfigured `permitAll()` (anonymous authentication on): still 401, not frozen; an `AnonymousAuthenticationToken` principal is not a caller | `ApplyPermitAllWebTest`, `TuningApplyWebExtensionTest` |
| Real **WebFlux** app (Reactor Netty) with actuator + reactive Spring Security (`SecurityWebFilterChain`, HTTP Basic): the write operation is reachable over WebFlux; no credentials 401 (nothing audited); wrong role 403 `ROLE_REQUIRED` with `who`; right role: `UNKNOWN_KNOB` 400 and `freeze` 200, both with `who` recorded. `anonymous()` + `permitAll()`: GET served, every POST 401 `NOT_AUTHENTICATED` (`who=anonymous`), not frozen, nothing kept in the in-memory audit | `ApplyReactiveSecurityWebTest` |
| **Real `ThreadPoolTaskExecutor`** (`AbortPolicy`, core = max = 2), open-loop submitter (130 tasks/s, seeded jitter, 20 ms tasks, counts the `TaskRejectedException`s it gets): (a) bounded queue → ST-REC-EXEC-001 `maxPoolSize` 2→3, written as `max=3`, CONFIRMED on R6; (b) tasks slow from 20 to 45 ms above 2 concurrent → R6 "more tasks are rejected" → REVERTED to 2, threads above it retire; (c) unbounded queue → `corePoolSize` 2→3 across the maximum: apply writes `max=3, core=3`, manual revert writes `core=2, max=2`. In (a) and (b) the rejections counted for R6 equal those the submitter received from `AbortPolicy` | `ExecutorApplyIntegrationTest` |
| **Real HikariCP + H2 + Micrometer**, advise: 15 ms holds → INCREASE 2→3 (cap 13); budget cap 2 → HOLD; 700 ms holds → "do not raise" | `HikariContentionIntegrationTest` |
| Real Boot app: `/actuator/tuning` content, POST rejected in advise mode | `BrokenConfigApplicationTest` |
| Starved pool (failing database) → `ST-REC-HIKARI-004` HOLD; a few starved samples without timeouts (filling after a raise) still raise; timeouts with a full pool still raise | `AdvisorTest` |
| `BUDGET_EXCEEDS_SERVER`: refused above the server's limit, allowed at it, not checked for unknown databases or before the limit was read, never for a DECREASE; plan line; H2 is unsupported | `ApplyGuardTest`, `ApplyPlansTest` |
| **Real PostgreSQL**, real Boot replicas, open-loop load: raise confirmed, slow SQL held, regression reverted once without oscillation, 3 replicas raising at once within budget, short `maxLifetime`, one replica reverting while two raise, another service holding connections, surge pod (breach detected when not counted, none when counted), `BUDGET_EXCEEDS_SERVER` (100 vs 20), failing database (role `CONNECTION LIMIT`) not raised | `springtune-loadtests` ([docs/loadtests.md](loadtests.md)) |

The unit and H2 tests run on Spring Boot 4.1 and 3.5; the load suites on Spring Boot 4.1 only (PostgreSQL 16
locally, `postgres:17-alpine` in CI). Not verified: production metric registries other than `SimpleMeterRegistry`,
WebFlux beyond the write operation's security (`ApplyReactiveSecurityWebTest`), MySQL/MariaDB (the server-limit query is written but untested), and replicas on separate
machines (the load suite runs them in one JVM).
