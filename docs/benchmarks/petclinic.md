# PetClinic: what the starter recommends, and what it does not

Spring PetClinic with the SpringTune starter in `advise` mode, against a local PostgreSQL 16, under a seeded
open-loop load. The question is **which recommendation rules fire, with which signals, in four pre-registered
scenarios**. It is not a performance benchmark: no latency, throughput or "faster" number is reported, and a rule
that fires is reported as *fired, with these signals*, not as *correct*.

Delivery brief: [iteration 6](../delivery/iteration-6-brief.md), row 7. Timeboxed.

## Pre-registration

Written and committed **before the first scenario run** (see this file's git history: the results section was
added in a later commit). Nothing below this section was changed after the runs except to add results.

### Fixed inputs

| Input | Value |
|---|---|
| Application | [spring-petclinic](https://github.com/spring-projects/spring-petclinic) at `500158f732419217507c7656904b8e6aa1bcc0d6` (the corpus SHA; Spring Boot 4.1.0, actuator included, `management.endpoints.web.exposure.include=*` already in its `application.properties`) |
| Change to PetClinic | one dependency, `io.github.winkytek.springtune:springtune-spring-boot-starter`, added by [`scripts/petclinic/petclinic.patch`](../../scripts/petclinic/petclinic.patch). No code, no endpoint, no query changed |
| SpringTune | **0.4.0 from Maven Central** (reproducible by anyone; `HikariAdvisor` and `SpringTuneProperties` are byte-identical between `v0.4.0` and the base of this iteration). If Central refuses, the locally built 0.4.2-SNAPSHOT with its commit; the results say which |
| Database | PostgreSQL 16 (`/usr/lib/postgresql/16/bin`), local, port 55433, data directory in the work directory, PetClinic's `postgres` profile and its own `schema.sql`/`data.sql` (10 owners, 13 pets, 6 vets) |
| Machine | one 4-vCPU sandbox VM shared by the load driver, the application and PostgreSQL |
| Load | [`scripts/petclinic/LoadDriver.java`](../../scripts/petclinic/LoadDriver.java): open loop at a fixed rate (requests are scheduled on a clock, not after the previous response), exponential inter-arrival jitter from a logged seed. Mix: 50% owner search `GET /owners?lastName=<one of "", Davis, Franklin, Black, Coleman, Zzz>`, 35% owner details `GET /owners/{1..10}`, 15% `GET /vets.html`. Redirects are not followed (a unique search hit is a 302). Request timeout 30 s; at most 2,000 requests in flight, beyond which a request is counted as *skipped*, never sent. Output: counts by status, errors, skipped. No latency is recorded |
| Seeds | scenario base seed + run number: S1 `61001..61003`, S2 `62001..62003`, S3 `63001..63003`, S4 `64001..64003`, calibration `62000` |
| Each run | fresh PostgreSQL data directory and fresh application (so the sample buffer starts empty); load starts when `/actuator/health` is UP; `GET /actuator/tuning` and `GET /actuator/metrics/system.cpu.usage` every 30 s for **7.5 minutes** of load; then stop. The default 5-minute window is first covered about 5 minutes after start, so the polls from minute 6 on are the evaluated ones |
| Runs | 3 per scenario if the timebox allows, otherwise the count is stated |

### SpringTune settings (0.4.0 defaults unless the scenario says otherwise)

`springtune.mode=advise`; `sampling.interval=10s`; `hikari.window=5m`, `saturated-ratio=0.8`, `slow-usage=500ms`,
`step=0.25`; `low-utilization-window=24h`, `low-utilization=0.2`; no `springtune.budget.*` (so no budget cap, and an
INCREASE is MEDIUM confidence). HikariCP defaults (`minimumIdle = maximumPoolSize`, `connectionTimeout=30s`) unless
the scenario changes `maximum-pool-size`.

How the rules decide (from `HikariAdvisor`, `v0.4.0`), which the expectations below follow:

- With the 5-minute window covered (≥ 3 samples, oldest within one interval of 5 minutes ago), compute the share
  of samples with `pending > 0` (threads waiting for a connection, an instantaneous read every 10 s).
- If ≥ 80%: **HIKARI-004** HOLD if the pool held fewer than its maximum while threads waited in ≥ 50% of samples (or
  timeouts occurred while it did); otherwise **HIKARI-002** HOLD if the mean connection hold time over the window
  > 500 ms (or hold-time metrics are missing); otherwise **HIKARI-001** INCREASE to `current + max(1, ceil(current
  × 0.25))`.
- Otherwise, only if the 24-hour window is covered: **HIKARI-003** DECREASE when no sample ever had a waiting thread
  and peak active < 20% of the pool.
- 0.4.0 has **no per-rule evaluation status** (that is iteration-6 row 4): an empty `recommendations[]` does not say
  whether a rule evaluated and stayed quiet or is still collecting. Here *quiet* is inferred from elapsed time
  (window covered) and the published signals.

### Scenarios and expectations

| # | Scenario | Settings | Rate | Expectation (what we predict before running) |
|---|---|---|---|---|
| S1 | Starter defaults, PetClinic's default pool | `maximum-pool-size` default (10) | 50 req/s | **No recommendation.** PetClinic's queries take milliseconds; at 50 req/s threads should almost never wait, far below the 80% threshold, so HIKARI-001/002/004 stay quiet; HIKARI-003 cannot evaluate in 7.5 minutes (24 h window); no `ST-REC-EXEC-*` (no executor backlog). Specifically: **no INCREASE** in any evaluated poll |
| S2 | Constrained pool | `spring.datasource.hikari.maximum-pool-size=2` | from the calibration below | **ST-REC-HIKARI-001 INCREASE 2 → 3**, MEDIUM confidence, evidence `samplesWithWaitingThreads` ≥ 80% and `meanConnectionHoldTime` < 500 ms, in the evaluated polls of every run. Risk named in advance: on 4 shared vCPUs the CPU may be the real limit (threads holding a connection get descheduled), so the rule may fire where a larger pool would not help; SpringTune's signals cannot tell those apart, so `system.cpu.usage` is recorded next to each poll. If CPU contention pushes the mean hold above 500 ms, HIKARI-002 would fire instead: that would be a miss of this prediction, reported as such |
| S3 | Database-side slowdown | pool default (10); a separate `psql` session repeats `BEGIN; LOCK TABLE owners IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(2.85); COMMIT;` then sleeps 0.15 s, for the whole run | 50 req/s | **ST-REC-HIKARI-002 HOLD ("do not raise the pool")**, HIGH confidence, evidence `meanConnectionHoldTime` > 500 ms. **Not** an INCREASE. Reasoning: 85% of requests touch `owners`, so the ten connections are all blocked within about 0.25 s of each 2.85 s lock, and threads wait in roughly 85–90% of samples; a blocked connection is held for about 1.4 s on average. Risk named in advance: if the waiting share lands below 80% (the instantaneous sample can miss), no rule fires: a miss, reported |
| S4 | Decrease, **compressed window: `low-utilization-window=5m` instead of 24 h** | pool default (10); `springtune.hikari.low-utilization-window=5m` | 5 req/s | **ST-REC-HIKARI-003 DECREASE 10 → 2** (peak active sampled ≤ 1, so `max(2, 2 × peak)` = 2), MEDIUM confidence. Only because the window is compressed: with the default 24 h this cannot fire in a run this short. The compressed window says nothing about whether 5 minutes of quiet justify a decrease in a real service |

**S2 calibration (pre-registered procedure, not a scenario).** PetClinic's capacity on this machine is unknown
before running, and a rate the pool absorbs would make S2 a test of nothing. So, once, before the S2 runs: pool 2,
seed `62000`, steps of 100, 200, 400, 800 and 1,200 req/s for 40 s each, polling
`/actuator/metrics/hikaricp.connections.pending` every second. **S2 uses the lowest step at which `pending > 0` in
≥ 95% of the polls of the step's last 30 s**; if no step reaches it, 1,200 req/s. The calibration output is committed
with the runs. The rule is fixed here so the rate cannot be picked after seeing S2's recommendations.

### What counts as a match

- A scenario **matches** when every evaluated poll (minute 6 onwards) of every run shows the predicted rule ID and
  action (or, for S1, no recommendation), and no poll shows an INCREASE where none was predicted.
- Anything else is a **miss** and is published with its signals and our best explanation. A miss is a finding, not a
  failure.

### Limitations stated in advance

- One 4-vCPU sandbox shared by the load driver, the application and PostgreSQL; synthetic load on a 10-owner
  dataset; one PostgreSQL on localhost. Nothing here represents a production service.
- Not a design partner and not a user: it does not count toward bar 1 of the apply ladder or gate 2.
- No latency, throughput or "faster" claim is made from these runs; the driver does not record latency.
- That a rule fired is not evidence that following it would help.
