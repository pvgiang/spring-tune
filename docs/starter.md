# Runtime starter (`springtune-spring-boot-starter`)

> Trying it in staging and willing to tell us what it recommended? See [design partners](design-partners.md).

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

## Evaluation status: is an empty `recommendations[]` healthy? (after 0.4.1)

> Arrives in the release after 0.4.1. On 0.4.1 and earlier the snapshot has no `evaluations` field.

`GET /actuator/tuning` lists, next to `recommendations`, an **`evaluations`** entry for every rule of every
component, computed at each sampler tick **in the same pass that decides the recommendation** (one function returns
both, so they cannot disagree). Shape (values from `RuleEvaluationTest` scenarios, component id illustrative):

| `status` | Meaning | Extra fields |
|---|---|---|
| `COLLECTING` | The retained samples do not cover the rule's window yet; no decision is made on partial evidence | `seenSamples` / `minSamples` (3), `seenSeconds` / `neededSeconds` (window minus one sampling interval) |
| `NO_SIGNAL` | A meter the rule needs is missing, so it can never fire. Today only `ST-REC-HIKARI-001`, which needs the HikariCP Micrometer timers (add `spring-boot-starter-actuator`; a step-only registry such as Datadog counts as missing, see [Caveats](#caveats)) | `missingMeters`: `hikaricp.connections.acquire` and/or `hikaricp.connections.usage` |
| `QUIET` | Evaluated over a covered window, nothing to recommend | `supersededBy`: the rule that fired instead for this pool (e.g. 001 is QUIET with `supersededBy: ST-REC-HIKARI-002` when 002 says "do not raise"), else `null` |
| `RECOMMENDING` | This rule produced the component's current entry in `recommendations` (HOLD included) | |

```json
"evaluations": [
  { "component": "hikari:orders-pool", "ruleId": "ST-REC-HIKARI-001", "status": "QUIET", "seenSamples": null,
    "minSamples": null, "seenSeconds": null, "neededSeconds": null, "missingMeters": null, "supersededBy": null },
  { "component": "hikari:orders-pool", "ruleId": "ST-REC-HIKARI-003", "status": "COLLECTING", "seenSamples": 31,
    "minSamples": 3, "seenSeconds": 310, "neededSeconds": 86390, "missingMeters": null, "supersededBy": null } ]
```

**What a first user sees, with the defaults** (`springtune.sampling.interval=10s`, `springtune.hikari.window=5m`,
`springtune.executor.window=5m`, `springtune.hikari.low-utilization-window=24h`), counted from the first sample of
each component (a Hikari pool is sampled only once it has started, i.e. after its first connection; an executor once
it is initialized):

| When | `evaluations` | `recommendations` |
|---|---|---|
| Hour 0, before the first tick (10 s) | `[]` | `[]` |
| First tick to ~5 min | every rule `COLLECTING`; 001, 002, 004 and `ST-REC-EXEC-001` need 290 s, 003 needs 86 390 s | `[]` |
| After 5 min | 001, 002, 004, EXEC-001: `QUIET`, `RECOMMENDING` or (001 without hold-time timers) `NO_SIGNAL`; 003 still `COLLECTING` | entries from the 5-minute rules only |
| After 24 h | 003 also `QUIET` or `RECOMMENDING` | may include a 003 DECREASE |

A restart starts again from hour 0 (samples are kept in memory only). In `observe` mode nothing is evaluated and
`evaluations` stays `[]`.

## Shareable report: `GET /actuator/tuning/report` (after 0.4.1)

> Arrives in the release after 0.4.1. On 0.4.1, paste the relevant entry of plain `GET /actuator/tuning` into the
> feedback form and replace names yourself.

A read operation on the same `tuning` endpoint (same exposure, same security as `GET /actuator/tuning`; any other
path below it is 404). It returns **strictly less** than the snapshot, built only from allowlisted values; nothing is
scrubbed afterwards:

- copied: enum names, numbers, instants, the rule ids SpringTune defines (`ST-REC-HIKARI-001` to `-004`, `ST-REC-EXEC-001`; a rule id posted in a request is copied only if it is one of these), setting names from a fixed list
  (`maximumPoolSize`, `minimumIdle`, `connectionTimeout`, `idleTimeout`, `maxLifetime`, `leakDetectionThreshold`,
  `corePoolSize`, `maxPoolSize`, `queueCapacity`), the two HikariCP meter names, upper-case refusal codes, and evidence
  entries whose key is one of the rules' own evidence keys **and** whose value is a number with an optional unit
  (`80%`, `40 ms`, `5m`, `3 of 10`);
- never copied: component ids (pool and bean names), knob ids and sources, signals, recommendation `reason` and
  `relatedRuleIds`, non-numeric evidence, apply plans, the apply block's `frozenReason`, `requiredRole`, guardrails and
  knobs, and from the audit `who`, `why`, `reasons`, `evidence` and the capacity line. The report lists these
  categories in `omitted`.
- **Pseudonyms**: components are `<kind>#<n>` (`hikari#1`, `executor#2`), numbered per kind in the order of their
  component id (sorted), within this process; a knob is `hikari#1.maximumPoolSize`. The mapping is not in the report;
  the numbering stays stable while the set of components does not change (normally the life of the process).
- Each recommendation carries a `feedbackUrl`: the [recommendation feedback form](https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml)
  with the title, `version`, `boot`, `recommended` and `payload` (that entry as JSON) pre-filled, at most 8 000
  characters.

Proven by `TuningReportAdviseWebTest` and `TuningReportApplyWebTest` (real web apps with Spring Security, serialized by
the actuator's own JSON mapper on Boot 4.1 and 3.5): with a pool named `orders-db.prod.internal`, an executor bean
named `jdbc:postgresql://10.1.2.3:5432/orders`, principal `alice@corp.example`, reason `ACME-123 deploy`, an
evidence key containing the pool name and a pgjdbc-style exception text in every free-text field, none of these
strings (and no `jdbc:`, IPv4 address, `@` or `.internal`) appears in the report or in a URL-decoded `feedbackUrl`;
every string leaf matches the report grammar. This is a check of what SpringTune copies; read the report before you
paste it.

Output of `TuningReportAdviseWebTest` (the hostile fixture; Spring Boot 4.1.2; the payload part of the URL elided).
The fixture is a hand-built snapshot, not a running service: its `evaluations` and audit are forged to exercise every
field, so it shows what a real service never does (an executor `NO_SIGNAL` naming a HikariCP meter, no
`ST-REC-HIKARI-004` entry, `verdicts` in advise mode):

```json
{
  "format": "springtune-report/1", "springtuneVersion": "0.4.2-SNAPSHOT", "springBootVersion": "4.1.2",
  "javaVersion": 21, "mode": "ADVISE", "generatedAt": "2026-11-09T10:00:00Z",
  "budget": { "databaseMaxConnections": 100, "maxReplicas": 6, "safetyFactor": 0.8, "perInstanceCap": 13 },
  "components": [
    { "ref": "executor#1", "kind": "executor", "samples": 31, "knobs": { "maxPoolSize": 4 } },
    { "ref": "hikari#1", "kind": "hikari", "samples": 31, "knobs": { "maximumPoolSize": 10, "minimumIdle": 10 } } ],
  "evaluations": [
    { "component": "hikari#1", "ruleId": "ST-REC-HIKARI-001", "status": "RECOMMENDING", "seenSamples": null,
      "minSamples": null, "seenSeconds": null, "neededSeconds": null, "missingMeters": null, "supersededBy": null },
    { "component": "hikari#1", "ruleId": "ST-REC-HIKARI-002", "status": "QUIET", "seenSamples": null,
      "minSamples": null, "seenSeconds": null, "neededSeconds": null, "missingMeters": null,
      "supersededBy": "ST-REC-HIKARI-001" },
    { "component": "hikari#1", "ruleId": "ST-REC-HIKARI-003", "status": "COLLECTING", "seenSamples": 31,
      "minSamples": 3, "seenSeconds": 310, "neededSeconds": 86390, "missingMeters": null, "supersededBy": null },
    { "component": "executor#1", "ruleId": "ST-REC-EXEC-001", "status": "NO_SIGNAL", "seenSamples": null,
      "minSamples": null, "seenSeconds": null, "neededSeconds": null,
      "missingMeters": [ "hikaricp.connections.usage" ], "supersededBy": null } ],
  "recommendations": [ {
    "ruleId": "ST-REC-HIKARI-001", "knob": "hikari#1.maximumPoolSize", "action": "INCREASE",
    "currentValue": 10, "recommendedValue": 13, "confidence": "HIGH",
    "evidence": { "window": "5m", "samples": "31", "samplesWithWaitingThreads": "100%",
                  "meanConnectionHoldTime": "40 ms", "peakActiveConnections": "3 of 10", "budgetCap": "13" },
    "createdAt": "2026-11-09T10:00:00Z",
    "feedbackUrl": "https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml&title=%5Brecommendation%5D%20ST-REC-HIKARI-001%3A%20INCREASE%2010%20to%2013&version=0.4.2-SNAPSHOT&boot=4.1.2&recommended=13&payload=%7B%22ruleId%22%3A%22ST-REC-HIKARI-001%22…" } ],
  "verdicts": [
    { "ref": "change-2", "at": "2026-11-09T10:00:00Z", "action": "apply", "ruleId": "ST-REC-HIKARI-001",
      "knob": "hikari#1.maximumPoolSize", "before": { "maximumPoolSize": 10, "minimumIdle": 10 },
      "after": { "maximumPoolSize": 13, "minimumIdle": 13 }, "outcome": "CONFIRMED", "verdict": "OK", "code": null,
      "capacityObtained": false, "finishedAt": "2026-11-09T10:05:30Z" },
    { "ref": null, "at": "2026-11-09T10:00:00Z", "action": null, "ruleId": null, "knob": null, "before": {},
      "after": {}, "outcome": "REFUSED", "verdict": null, "code": "NO_CURRENT_RECOMMENDATION",
      "capacityObtained": null, "finishedAt": "2026-11-09T10:00:00Z" } ],
  "omitted": [ "componentIds", "knobIds", "knobSources", "signals", "recommendation.reason",
    "recommendation.relatedRuleIds", "recommendation.evidence.nonNumeric", "applyPlans", "apply.frozenReason",
    "apply.requiredRole", "apply.guardrails", "apply.knobs", "audit.who", "audit.why", "audit.reasons",
    "audit.evidence", "audit.capacity" ]
}
```

In that fixture the evidence entries keyed by the pool name, with an exception text or an IP address as value, or
with a non-numeric value (`unavailable`) were dropped; so was a recommendation whose rule id embedded the pool name, a
component of an unknown kind and every field of the forged second audit entry except its enums. `verdicts` is `[]`
outside apply mode.

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

> **Known limits of apply mode.** Experimental and opt-in; no production user yet.
>
> - **A verdict judges "nothing got worse", not "capacity was obtained".** A raise can be CONFIRMED while the database
>   refused the new connections. After 0.4.0 this is **visible, not hidden**: every verified raise records
>   `capacityObtained` (`true`/`false`) with a one-line reason, in the audit entry, the `POST` response, the snapshot
>   and the `springtune.changes{capacity_obtained}` tag. It still does **not** change the verdict or revert (a revert
>   on connect failures would act during exactly the outage where it helps least); `ST-REC-HIKARI-004` holds further
>   raises of that pool. Alert on `springtune.changes{capacity_obtained="false"}`.
> - **The server limit is re-read once per apply attempt**, not watched continuously. A lowered `max_connections`
>   is caught at the next apply (`BUDGET_EXCEEDS_SERVER`); if that re-read fails, the raise is refused
>   (`SERVER_LIMIT_UNKNOWN`), never approved on a cached value. Between applies the plan shows the last value read
>   and when.
> - Ephemeral, per instance, one change in flight per process; see [Caveats](#caveats).

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
| Raising a Hikari pool: `database-max-connections` must not exceed what the **server** accepts from application roles, **re-read for every apply attempt** through the pool (PostgreSQL `max_connections − superuser_reserved_connections − reserved_connections`, MySQL/MariaDB `@@max_connections`; other databases are not checked and the apply plan says so) | `BUDGET_EXCEEDS_SERVER` (409) |
| Raising a Hikari pool: that re-read must succeed within the pool's `connectionTimeout` (at most 30 s). Fails closed: a failed or unanswered read refuses the raise and names the last known value and when it was read; a DECREASE is never refused for it | `SERVER_LIMIT_UNKNOWN` (409) |
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

Recorded with every verdict, **never** a reason to revert:

| Field | Value | Measured |
|---|---|---|
| `capacityObtained` (Hikari) | `true` when HikariCP's `getTotalConnections()` reached the new `maximumPoolSize` at any sampler tick from the apply to the verdict; `false` otherwise, with a reason such as `Capacity NOT obtained: the pool never exceeded 4 connections; target 5, although threads waited for a connection (connection creation refused or failing?)` or `...; no thread waited for a connection while it was smaller (no demand for more yet)` | each tick (`springtune.sampling.interval`), so a connection that opened and retired between two ticks is missed |
| `capacityObtained` (executor) | the same with the executor's pool size against the new `corePoolSize`/`maxPoolSize` ("tasks were queued" as demand). Thread creation rarely fails, but the pool grows only on demand just like HikariCP, so the flag says whether the new size was used | each tick |
| `capacityObtained` = `null` | a DECREASE (applied lazily: there is nothing to obtain), or a component that did not report its size | |

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
`verdict`, `reasons`, `evidence` (B → V for each signal), `capacityObtained` and `capacity` (its reason line, also
the last entry of `reasons`). The `POST` response's `change` is the same entry.

Every audit entry is also logged on the logger **`springtune.audit`** (WARN for `REVERT_FAILED`) and published as a
`SpringTuneChangeEvent` (listen to it to forward changes). The counter
**`springtune.changes{outcome, capacity_obtained}`** counts final outcomes (`confirmed`, `reverted`,
`kept_inconclusive`, `superseded`, `revert_failed`, `apply_failed`, `refused`, `frozen`) and whether a verified raise
obtained its capacity (`true`, `false`, or `not_applicable` for decreases, refusals and freezes): at most 24 series.

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
- **Server limit check.** In apply mode with a budget, SpringTune reads the server's connection limit once after
  the pool starts (for the apply plan) and **again for every apply attempt** of a Hikari knob, with one connection
  borrowed from the pool like any other request, on a short-lived thread. The `POST` waits for it at most the pool's
  `connectionTimeout` (capped at 30 s); the controller's lock is not held meanwhile, so the sampler tick never waits
  for it. Requests refused anyway (unauthenticated, invalid, frozen, change in flight, cooldown) read nothing. If
  the re-read fails or does not answer in time, the raise is refused with `SERVER_LIMIT_UNKNOWN`: SpringTune does
  not approve a raise against a cached limit, because the cached value is exactly what an operator may have just
  lowered, and refusing leaves the pool as it is. The plan keeps showing the last value read, `last checked at`
  its time, and the failed re-read. Decision note: iteration 4 skipped the check until the first read succeeded;
  the next release fails closed instead. The cost is that a pool so starved that one borrow times out cannot be raised
  through apply mode until a connection is free; such a pool usually gets `ST-REC-HIKARI-004` HOLD anyway.
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
  "evaluations": [ { "component": "hikari:orders-pool", "ruleId": "ST-REC-HIKARI-002", "status": "RECOMMENDING", ... } ],
  "applyPlans": [ ],
  "apply": null
}
```

## How it was verified

| Check | Test |
|---|---|
| Rules on synthetic signals (raise, cap, above cap, slow holds, missing metrics, lower, coverage) | `AdvisorTest` |
| Evaluation statuses: fresh pool `COLLECTING` with samples and seconds seen vs needed (13 samples, 120 of 290 s); no sample yet; healthy pool after 5 min `QUIET` with 003 still `COLLECTING`, after 24 h `QUIET` on every rule; missing usage timer `NO_SIGNAL` naming `hikaricp.connections.usage` (synthetic and on a real H2 pool); precedence as `QUIET` + `supersededBy`; executor `COLLECTING` then `QUIET`; snapshot lists the latest tick. Property test (4 000 seeded random windows over every rule): `RECOMMENDING` exactly for the rule of the recommendation present, at most one per pool, same recommendation as before statuses existed, every rule and every status reached | `RuleEvaluationTest` |
| `GET /actuator/tuning/report`: served next to the apply write operation and in advise mode, behind Spring Security (401 without credentials), 404 for any other selector; trap (hostile pool, bean, principal, reason, evidence key, exception texts: none survives, also not in the URL-decoded `feedbackUrl`); schema (every key and string leaf in the report grammar); pseudonym order; version fallback; evidence grammar | `TuningReportAdviseWebTest`, `TuningReportApplyWebTest`, `ApplySecurityWebTest`, `TuningReportTest` |
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
| `BUDGET_EXCEEDS_SERVER`: refused above the server's limit, allowed at it, not checked for unknown databases, never for a DECREASE; a failed re-read refuses a raise (`SERVER_LIMIT_UNKNOWN`, naming the last known value) but not a DECREASE; plan line; H2 is unsupported | `ApplyGuardTest`, `ApplyPlansTest` |
| Server limit re-read per apply: re-read on every attempt and a limit lowered after the first read refuses the next raise (`BUDGET_EXCEEDS_SERVER`); a failed re-read → `SERVER_LIMIT_UNKNOWN` with "last known limit 10, read at ..."; requests refused for other reasons read nothing; the re-read runs outside the controller lock and a blocked read does not block `tick()` or the snapshot; races with the unlocked read (two applies both reading → one change; freeze, disable or a changed live value during the read → refused; a cooldown ending between checks never skips the read); a reader `Error` does not wedge later re-reads | `ChangeControllerTest`, `ServerLimitRecheckTest` |
| Same on a real Hikari pool (H2, limit query stubbed): fresh value every call, cached value kept for the plan with `last checked at` after a failure, "never read successfully", a hanging read answered after `connectionTimeout` (250 ms) while `sample()` returns at once and a second apply joins the same read, H2 never re-read | `ServerLimitRecheckTest` |
| `capacityObtained`: raise reaching its target → `true`; CONFIRMED with the pool stuck below the target while threads wait → `false` with "connection creation refused or failing?", verdict and value unchanged, no revert; no demand → `false` "no demand for more yet"; recorded on a REVERTED raise too; DECREASE or unreported → `null`; `springtune.changes{capacity_obtained}` tag; audit log line | `ChangeControllerTest` |
| `capacityObtained` on real pools: Hikari 2 → 3 (H2) recorded; executor `maxPoolSize` 2 → 3 with a full queue → `true` and `springtune.changes{outcome=confirmed,capacity_obtained=true}` | `HikariApplyIntegrationTest`, `ExecutorApplyIntegrationTest` |
| **Real PostgreSQL**, real Boot replicas, open-loop load: raise confirmed, slow SQL held, regression reverted once without oscillation, 3 replicas raising at once within budget, short `maxLifetime`, one replica reverting while two raise, another service holding connections, surge pod (breach detected when not counted, none when counted; the replica the server refused reports `capacityObtained=false`, every other one `true`, matching `pg_stat_activity`), `BUDGET_EXCEEDS_SERVER` (100 vs 20), failing database (role `CONNECTION LIMIT`) not raised | `springtune-loadtests` ([docs/loadtests.md](loadtests.md)) |

The unit and H2 tests run on Spring Boot 4.1 and 3.5; the load suites on Spring Boot 4.1 only (PostgreSQL 16
locally, `postgres:17-alpine` in CI). Not verified: production metric registries other than `SimpleMeterRegistry`,
WebFlux beyond the write operation's security (`ApplyReactiveSecurityWebTest`), MySQL/MariaDB (the server-limit query is written but untested), and replicas on separate
machines (the load suite runs them in one JVM).
