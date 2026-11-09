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

## Results (2026-11-09)

Added after the runs. The pre-registration above is unchanged (compare this file at commit `3da677f`).

**What ran.** All four scenarios, **3 runs each (12 runs), none interrupted**, plus the S2 calibration, between
14:13 and 15:54 UTC on 2026-11-09, with the scripts at commit `0e842aa`. Starter **0.4.0 resolved from Maven
Central** (`springtune-spring-boot-autoconfigure-0.4.0.jar` SHA-256 `259fbf5c…bdec4`). One 30-second plumbing check
(S3 settings, seed 63000) ran at 11:27 UTC, after the pre-registration commit and before the scripts were committed;
its output was discarded and it is not one of the 12 runs (too short for any window to be covered).

Raw outputs: [`petclinic/2026-11-09-runs/`](petclinic/2026-11-09-runs/), one directory per run with
`scenario.json` (rate, seed, settings), `environment.txt` (nproc 4, OpenJDK 21.0.12, PostgreSQL 16.15, PetClinic SHA,
Boot 4.1.0, starter version and jar hash, spring-tune commit), `driver-summary.json`, `polls.jsonl` (each
`GET /actuator/tuning` **trimmed** to mode, budget, components with signals and knob values, recommendations and
apply plans, plus `system.cpu.usage`) and `last-snapshot-full.json` (the last poll untrimmed).
[`summary.tsv`](petclinic/2026-11-09-runs/summary.tsv) is produced by
[`scripts/petclinic/evaluate.sh`](../../scripts/petclinic/evaluate.sh).

**Which polls count.** A poll is *evaluated* when its `generatedAt` falls between minute 6 of the driver's schedule
and its end (minute 7.5). The polls were meant to be 30 s apart, but each poll's two HTTP calls take time under
load, so the real interval stretched; runs have 2 to 4 evaluated polls, and 0 to 3 later polls taken after the load
stopped (listed separately, not used for the verdict).

### Summary

| # | Predicted | Observed in evaluated polls (all 3 runs) | Verdict |
|---|---|---|---|
| S1 | no recommendation | none in 10 of 10 evaluated polls | **match** |
| S2 | HIKARI-001 INCREASE 2 → 3 | **ST-REC-HIKARI-001 INCREASE 2 → 3**, MEDIUM, in 8 of 8 | **match** (with the CPU caveat below) |
| S3 | HIKARI-002 HOLD, not INCREASE | **ST-REC-HIKARI-001 INCREASE 10 → 13**, MEDIUM, in 9 of 9 | **miss** |
| S4 (compressed window, 5 m) | HIKARI-003 DECREASE 10 → 2 | **ST-REC-HIKARI-003 DECREASE 10 → 2**, MEDIUM, in 12 of 12 | **match** |

No `ST-REC-EXEC-*` recommendation in any poll of any run (Boot's `applicationTaskExecutor` was watched and stayed
idle; PetClinic submits nothing to it).

### S1: starter defaults, pool 10, 50 req/s: quiet, as predicted

Seeds 61001–61003. Every poll of every run: `recommendations: []`, `waitingThreads` 0 in every poll, `active` 0 or
1 of 10. All requests answered (200 and 302 only, no errors, nothing skipped). In 0.4.0 an empty list at minute 7 is
indistinguishable from "still collecting" except by the clock; a first user sees exactly this, which is the case
for the per-rule evaluation status of iteration-6 row 4.

### S2: pool 2, 1,200 req/s: HIKARI-001 fired, as predicted; the pool was not the only limit

**Calibration** (seed 62000, [`s2-calibration/`](petclinic/2026-11-09-runs/s2-calibration/)): share of 1-s polls
with `hikaricp.connections.pending > 0` was 3% at 100 req/s, 13% at 200, 63% at 400, **20% at 800 and 23% at
1,200**. No step reached 95%, so by the pre-registered rule S2 ran at **1,200 req/s**. The non-monotonic shape is
itself a finding: from 800 req/s the driver hit its 2,000-in-flight cap (it skipped 12,545 of 31,889 scheduled
requests at 800 and 26,898 of 47,730 at 1,200), i.e. the application as a whole, not the pool, was the limit, and
requests queued before reaching the pool.

**Runs** (seeds 62001–62003): every evaluated poll carried

> ST-REC-HIKARI-001 INCREASE 2 → 3, MEDIUM. Evidence: window 5m, 30–31 samples, `samplesWithWaitingThreads` 80–90%,
> `maxWaitingThreads` 37–165, `meanConnectionHoldTime` 1 ms, `meanAcquireTime` 12–69 ms, budget not configured.

`system.cpu.usage` was 87–96% in every poll under load, and the driver skipped 33–46% of scheduled requests at its
in-flight cap in every run (no HTTP errors). So the rule fired on its own terms (threads waited in ≥ 80% of samples,
connections came back in about 1 ms), but the machine's CPU was saturated at the same time, and nothing in
SpringTune's signals distinguishes "the pool is the bottleneck" from "the pool is one of several bottlenecks on a
CPU-bound host". We make **no claim that a pool of 3 would help** here; only that the rule fired with these signals.
The pre-registered risk (CPU contention) materialized; the predicted rule and action still held.

### S3: database lock, pool 10, 50 req/s: HIKARI-001 INCREASE fired instead of "don't raise" (miss)

Seeds 63001–63003; the lock holder ran for the whole of each run (its error log is empty; `active` was 10 of 10 and
`waitingThreads` 52–99 in every poll under load, CPU 5–7%). Every evaluated poll carried

> ST-REC-HIKARI-001 INCREASE 10 → 13, MEDIUM: "connections were returned quickly (142 ms average hold), so the pool
> size is the bottleneck". Evidence: `samplesWithWaitingThreads` 80–100%, `maxWaitingThreads` 100–190,
> **`meanConnectionHoldTime` 140–146 ms**, **`meanAcquireTime` 762–1,111 ms**, threshold 500 ms.

**This is the outcome the pre-registration said must not happen**: the database was the bottleneck (a table lock),
and raising the pool to 13 would give the lock three more blocked connections. Why the rule missed:

- The pre-registered estimate of about 1.4 s mean hold was wrong. The *mean* hold time is diluted: in each 3-s cycle
  roughly ten checkouts are held for up to 2.85 s, then in the 0.15-s gap the backlog of about 140 requests drains,
  each with one or more millisecond-long checkouts (queries that do not touch `owners` never block at all). The mean
  lands near 140 ms, under the 500 ms `slow-usage` threshold.
- The slowness shows up where the rule does not look as a discriminator: acquire time (about 0.8–1.1 s) was 5–8×
  the mean hold time. In S2, where the pool really was the narrow point, hold was 1 ms and acquire 12–69 ms.
- So HIKARI-002, as specified ("mean hold > 500 ms"), does not catch a lock convoy or any slowdown that hits a
  minority of checkouts hard. A candidate refinement (not implemented, not validated here): use a high percentile
  or the maximum of hold time from the `hikaricp.connections.usage` histogram, or hold back when a share of the
  window's checkouts exceed `slow-usage`. Any change must be re-tested against S2 so it does not suppress that one.

This miss is the most useful result of the run. It is published as is.

### S4: compressed window (`low-utilization-window=5m`), pool 10, 5 req/s: HIKARI-003 DECREASE, as predicted

Seeds 64001–64003. Every evaluated poll carried ST-REC-HIKARI-003 DECREASE 10 → 2, MEDIUM, evidence window 5m,
30–31 samples, `peakActiveConnections` 0 or 1 of 10, `threadsEverWaiting` no. It fires only because the window was
compressed to 5 minutes; with the default 24 h it cannot fire in a run this short, and five quiet minutes say
nothing about whether a real service should shrink its pool.

### Limitations

- One 4-vCPU sandbox VM shared by the load driver, PetClinic and PostgreSQL; in S2 the CPU was saturated, which is
  a property of this setup, not of PetClinic or SpringTune.
- Synthetic, seeded load on PetClinic's 10-owner dataset; one PostgreSQL on localhost; three runs per scenario.
- Not a design partner and not a user. It does **not** count toward bar 1 of the apply ladder or toward gate 2.
- No latency, throughput or "faster" claim: the driver records only response counts, and the in-flight and skip
  counts above describe the load shape, not performance.
- A rule that fired is not evidence that following it would help (S2), and S3 shows a case where following it
  would not.

### Reproduce

```bash
# from the spring-tune repository root; needs git, mvn, JDK 17+, jq, curl, PostgreSQL 16 binaries, root or sudo
export WORK=/tmp/springtune-petclinic PG_ROOT=/var/tmp/springtune-petclinic-pg   # PG_ROOT must be writable by postgres
scripts/petclinic/run.sh build                     # PetClinic @ 500158f7 + starter 0.4.0 from Maven Central
scripts/petclinic/run.sh calibrate                 # seed 62000; writes s2-calibration/result.json
export S2_RATE=1200                                # the calibration result on our machine; use yours
for s in S1 S2 S3 S4; do for n in 1 2 3; do scripts/petclinic/run.sh run $s $n; done; done
scripts/petclinic/evaluate.sh > summary.tsv
```

Seeds are fixed per scenario and run (S1 6100n, S2 6200n, S3 6300n, S4 6400n, calibration 62000), so the request
sequence is identical on any machine; what the rules see is not, because it depends on the machine's speed.
