# PetClinic: a hold check for the S3 miss (pre-registered)

Follow-up of the [S3 miss](petclinic.md#s3-database-lock-pool-10-50-reqs-hikari-001-increase-fired-instead-of-dont-raise-miss)
of the [PetClinic benchmark](petclinic.md). Delivery brief: [iteration 7](../delivery/iteration-7-brief.md), row 7,
whose bar is binding here. It is not a performance benchmark: no latency, throughput or "faster" number is reported.

The order of this file's sections is the order of the work, and the git history proves it: step 0 (a finding on the
committed data) was committed first, step 1 (the pre-registration) was committed alone before any new run, and
results were added only after the runs.

## Step 0: what the committed 2026-11-09 data can say

**Finding 1: the committed polls contain only means, so no max or percentile check can be evaluated on them.** Every
field of the Hikari component and of every recommendation's evidence in the 48 committed `polls.jsonl` files
(12 runs), as printed by `python3 -I scripts/petclinic/hold-step0.py`:

- Hikari signals: `active`, `holdTimeMetrics`, `idle`, `maximumPoolSize`, `sampledAt`, `status`, `waitingThreads`
- recommendation evidence: `budgetCap`, `maxWaitingThreads`, `meanAcquireTime`, `meanConnectionHoldTime`,
  `peakActiveConnections`, `samples`, `samplesWithWaitingThreads`, `slowHoldThreshold`, `threadsEverWaiting`,
  `window`

`meanConnectionHoldTime` and `meanAcquireTime` are window means (`Δ TOTAL_TIME / Δ COUNT`). Nothing records the
`hikaricp.connections.usage` MAX, a histogram bucket or a percentile, and the runs did not configure any (no
`management.metrics.distribution.*` property). So "evaluate P_max or P_share against the S3 data" is impossible:
new runs are needed (step 1).

**Finding 2: the acquire/hold mean ratio, from the committed data, per run** (evaluated polls only: `generatedAt`
between driver start + 360 s and start + 450 s, whole seconds, the rule of `scripts/petclinic/evaluate.sh`; only
polls that carry a recommendation have the two means):

| Scenario | Run | Evaluated polls | Polls with both means | Mean hold | Mean acquire | acquire / hold |
|---|---|---|---|---|---|---|
| S1 | 1 / 2 / 3 | 3 / 4 / 3 | 0 / 0 / 0 | n/a | n/a | n/a (no recommendation, so no means published) |
| S2 | 1 | 2 | 2 | 1 ms | 61–69 ms | 61–69 |
| S2 | 2 | 3 | 3 | 1 ms | 12 ms | 12 |
| S2 | 3 | 3 | 3 | 1 ms | 20–23 ms | 20–23 |
| S3 | 1 | 3 | 3 | 142–143 ms | 762–769 ms | 5.4 |
| S3 | 2 | 3 | 3 | 141–142 ms | 775–778 ms | 5.5 |
| S3 | 3 | 3 | 3 | 145–146 ms | 765–767 ms | 5.2–5.3 |
| S4 | 1 / 2 / 3 | 4 / 4 / 4 | 0 / 0 / 0 | n/a | n/a | n/a (DECREASE evidence has no means) |

On these six runs a cut somewhere between 5.5 and 12 would put S3 on one side and S2 on the other. It is still
rejected as a discriminator, for reasons the data show:

- **Same direction in both.** In S2 and in S3 acquire is several times the hold: threads queue for a connection in
  both. A ratio above 1 is the signature of a queue, not of where the queue comes from; the only reading the ratio
  supports ("acquire ≫ hold, so the pool is the narrow point") gives INCREASE in both scenarios.
- **The cut would be fitted after seeing the data**, on 3 seeds of one lock pattern, which is the overfitting the
  brief rejects. Nothing predicts where the cut belongs before the data.
- **S2's ratio is not known to better than a factor of about 3.** The evidence rounds the hold mean to whole
  milliseconds (`Format.millis`): "1 ms" is anything from 0.5 to 1.5 ms, so S2's ratios are 8–138, not 12–69. The
  gap to S3 (5.5) is smaller than the rounding uncertainty at its low end.
- **The ratio scales with the queue, i.e. with load.** S2 ran at 1,200 req/s and S3 at 50 req/s. Acquire time grows
  with the number of waiting threads; the same lock at a higher request rate would raise S3's ratio towards S2's.
  The committed data have no second rate per scenario, so they cannot show where either ratio would land.

So the acquire/hold ratio cannot be shown to separate S2 from S3 on the committed data in any way that is not a
post-hoc fit, and it is not a candidate below.

## Step 1: pre-registration

Written and committed **alone, before any new scenario run** (compare this commit's time with the `started_at` of
the first `driver-summary.json` under `petclinic/2026-11-15-runs/`). Nothing in this section is changed after the
runs; deviations found later are listed in the results section.

### What was checked before this commit (not a scenario run)

A plumbing check on 2026-11-15 between 10:59 and 13:42 UTC (interrupted in between): PetClinic with the locally
built starter, no load driver except a 25-second, 20 req/s check of the S5 edit (seed 1), a handful of single
requests and one manual 2-s table lock. Its output was not kept. It established:

- `--management.metrics.distribution.slo.hikaricp.connections.usage=500ms,1h` on the command line works on
  PetClinic's Boot 4.1.0: `/actuator/metrics` lists `hikaricp.connections.usage.histogram` with tag `le` values
  `0.5` and `3600`, and after a 1.7-s hold `COUNT` read 44 while `le:0.5` read 43 and `le:3600` read 44.
- **The bucket gauges are not cumulative.** With the default registry (Boot's `SimpleMeterRegistry`, the only one
  PetClinic has) the bucket counts are those of a decaying window: after the traffic stopped they fell to 0 within
  about 60 s while `COUNT` stayed at 44, and after a burst they fell from 648 to 55 to 0 over the next minute. So
  the "SLO bucket deltas" of the brief are meaningless here; P_share below uses the decaying counts directly, with
  the `1h` bucket as the denominator (it counts every checkout of the same decaying window). This is the reason for
  the second boundary.
- **The usage `MAX` decays over about 2–2.5 minutes**: a 3.04-s hold stayed the `MAX` from about 13:39:35 until
  13:41:58 and read 0 at 13:42:08, with no other traffic.
- The S5 mechanism works: with the trigger installed, `POST /owners/1/edit` answered 302 and raised the usage `MAX`
  to 3.038 s; the edit draws nothing from the seeded `Random`, so the seeded GET sequence is unchanged.

### Fixed inputs (changes against [petclinic.md](petclinic.md#fixed-inputs), all disclosed)

Everything in petclinic.md's fixed inputs holds, except:

| Input | Here |
|---|---|
| SpringTune | the **locally built** `0.4.3-SNAPSHOT` from this repository (0.4.2 is not on Maven Central, brief §2), built with `mvn -B -ntp -q install -DskipTests -Dinvoker.skip` at commit `86357dd` (the iteration-7 brief; `HikariAdvisor`, `HikariSample`, `HikariComponent`, `TimerReader`, `MicrometerSupport` as in `v0.4.2`). Each run's `environment.txt` records the SHA-256 of the autoconfigure jar **as packaged in the PetClinic jar**, so a later local install of the same SNAPSHOT cannot change what ran unnoticed |
| Command line | every run adds `--management.metrics.distribution.slo.hikaricp.connections.usage=500ms,1h` (`HOLD_CHECK=1`). This adds a histogram to one timer; it does not change what SpringTune reads today (`COUNT`, `TOTAL_TIME`) |
| Polls | on the wall clock: one poll at load start + 30 k s, k = 0…15, none after the end of the load; a slot more than 15 s late is skipped (visible by its absence). Each poll reads, in parallel, `GET /actuator/tuning` (trimmed as before), `system.cpu.usage`, and under `hold`: `hikaricp.connections.usage` and `hikaricp.connections.acquire` (`COUNT`, `TOTAL_TIME`, `MAX`), `hikaricp.connections.usage.histogram` at `le:0.5` and `le:3600`, and `hikaricp.connections.pending` |
| Work directories | `WORK=/var/tmp/springtune-petclinic-d`, `PG_ROOT=/var/tmp/springtune-petclinic-d/pgroot` (the `postgres` user cannot enter the scratch area), PostgreSQL port 55437, app port 18087 |
| S2 rate | **1,200 req/s, the registered calibration result of 2026-11-09** (`2026-11-09-runs/s2-calibration/result.json`). Not recalibrated |
| Machine | the same kind of 4-vCPU sandbox; other work may run on it at the same time (other worktrees build there). Each run records the load average at its start |

Starter settings: the defaults of petclinic.md (`advise`, 10 s sampling, 5 m window, `saturated-ratio` 0.8,
`slow-usage` 500 ms, `step` 0.25, no budget), unchanged.

### Scenarios and seeds (3 runs each, sequential)

| # | Scenario | Pool | Rate | Seeds | Expectation, **unchanged rule** |
|---|---|---|---|---|---|
| S1 | as registered in petclinic.md | 10 | 50 | 61001, 61002, 61003 | no recommendation |
| S2 | as registered | 2 | 1,200 | 62001, 62002, 62003 | HIKARI-001 INCREASE 2 → 3 |
| S3 | as registered: lock `owners` ACCESS EXCLUSIVE 2.85 s, release 0.15 s (3-s cycle) | 10 | 50 | 63001, 63002, 63003 | HIKARI-001 INCREASE 10 → 13 (the miss again) |
| S3′ (`S3P`) | the same lock with another duty cycle: **lock 1.9 s, release 0.1 s (2-s cycle)**: blocked holds a third shorter, a cycle two thirds as long, releases a third shorter | 10 | 50 | 63101, 63102, 63103 | HIKARI-001 INCREASE 10 → 13. If the unchanged rule gives no INCREASE here, S3′ cannot discriminate and is reported as such |
| S4 | as registered (`low-utilization-window=5m`) | 10 | 5 | 64001, 64002, 64003 | HIKARI-003 DECREASE 10 → 2 |
| S5 | **S2's pool and rate plus one slow query a minute**: the driver sends `POST /owners/1/edit` (owner 1's own values, the telephone alternating between two valid numbers so an `UPDATE` is issued) at 30 s, 90 s, …; a `BEFORE UPDATE` trigger on `owners` (installed after startup, before the load) runs `pg_sleep(3)`. So one checkout a minute is held ≥ 3 s; nothing else writes. INCREASE is still right: the pool is the narrow point as in S2, and one 3-s query a minute does not change that | 2 | 1,200 | 65001, 65002, 65003 | HIKARI-001 INCREASE 2 → 3 |

Order: S1, S2, S3, S3′, S4, S5, each run 1, 2, 3, one at a time.

### Evaluated polls (fixed now; no post-hoc narrowing)

A poll is **evaluated** when its `generatedAt` lies in `[started_at + 360 s, started_at + duration_seconds]`, both
from the run's `driver-summary.json`, all truncated to whole seconds (exactly `scripts/petclinic/evaluate.sh`).
Every evaluated poll counts. A poll whose `/actuator/tuning` read failed counts as "no recommendation". A run with
no evaluated poll fails every "in 3 of 3 runs" condition it is part of. No poll is excluded for any other reason,
and the window is not changed after the runs.

### Candidates (exactly as evaluated)

Both candidates act only on an evaluated poll whose **unchanged** recommendation is `ST-REC-HIKARI-001 INCREASE`;
they may turn it into a HOLD and never change anything else (so S1 and S4 can differ from the unchanged rule only
if the unchanged rule itself emits an INCREASE there). For an evaluated poll at time `t` (its `generatedAt`), the
**window** is every poll of the same run without a read error whose `generatedAt` lies in `[t − 300 s, t]` (the
5-minute rule window, approximated by the 30-s polls; the starter samples every 10 s, a stated limitation). If the
window has fewer than 3 polls, or any window poll lacks the meter a candidate needs, the candidate **falls back** to
the unchanged rule (what the implementation would do with the meter missing).

- **P_max**: among the window polls whose Hikari signal `waitingThreads` > 0, the share whose `hold.usage.MAX`
  > 0.5 s (`slow-usage`). If at least one such poll exists and the share is **≥ 80%**: **HOLD** (reported as
  ST-REC-HIKARI-002, "do not raise the pool"). Otherwise the INCREASE stands.
- **P_share**: `share = Σ (hold.usage_le_1h − hold.usage_le_500ms) / Σ hold.usage_le_1h` over the window polls
  (the decaying-window counts, see above). If the denominator is 0: fall back. If `share` **≥ 2%**: **HOLD**
  (ST-REC-HIKARI-002). Otherwise the INCREASE stands. The 2% threshold is fixed now. Rough expectation from the
  lock geometry: about ten blocked checkouts per cycle against 100–150 checkouts per cycle gives 5–10% in S3 and
  S3′; S5 has a few slow checkouts against tens of thousands in a window, far below 0.1%.

Prediction (not part of the bar): P_max fails S5, because one 3-s hold a minute keeps the `MAX` above 0.5 s for the
2–2.5 minutes it lasts, i.e. in every poll; P_share passes S3, S3′ and S5.

### The bar (brief row 7, verbatim, binding)

> per candidate, all of: S3 and S3′ give no INCREASE in any evaluated poll, in 3 of 3 runs each; S2 and S5 give
> INCREASE as registered, in 3 of 3; S1 has no recommendation; S4 is unchanged; every existing advisor test and the
> 4,000-window property test pass, with any intended difference listed before the change.

Made exact: **S3, S3′**: no evaluated poll of any of the 3 runs carries an INCREASE (of any rule) after the
candidate. **S2, S5**: every evaluated poll of each of the 3 runs carries `ST-REC-HIKARI-001 INCREASE 2 → 3` after
the candidate. **S1**: no evaluated poll of any run carries a recommendation. **S4**: in every evaluated poll the
candidate's output equals the unchanged rule's output (reported next to it: whether that output is the registered
DECREASE 10 → 2). **Tests**: `AdvisorTest`, `RuleEvaluationTest` (including the 4,000-window property test
`recommendingExactlyWhenARecommendationIsPresent`, 3,000 + 1,000 windows against a copy of the 0.4.1 logic) and the
other Hikari tests of the autoconfigure module pass.

**Intended difference, listed before any change:** the existing tests build `HikariSample`s with no MAX and no
bucket data, so a candidate implemented with the pre-registered fallback ("meter missing → today's mean check")
gives exactly today's result on every one of them: **no existing test is expected to change**. New tests cover the
new branch. If an existing test needs a change, that is a deviation and is reported.

Because a candidate can only remove INCREASEs, it cannot repair a run where the unchanged rule does not give the
registered INCREASE in S2 or S5: such a run fails the bar for both candidates.

### Steps 3 and 4, fixed now

`scripts/petclinic/evaluate-hold.py` (run with `python3 -I`) computes, per run and evaluated poll, the unchanged
recommendation and each candidate's, and the bar per candidate. Only if a candidate passes every item is it
implemented in `HikariAdvisor` (with the fallback, evidence fields and documented confidence), tests first, and S3
re-run with 3 seeds (63001–63003) on the changed starter. Otherwise the rule is not changed and the negative result
is published here.

## Results (runs 2026-11-15 13:45 to 2026-11-16 00:55 UTC)

Added after the runs. The step-1 section above is unchanged (compare this file at commit `6f1d188`).

**Verdict.** Against the pre-registered bar:

- **P_max fails.** It gives HOLD instead of the right INCREASE in every evaluated poll of all three S5 runs.
- **P_share** passes the scenario part of the table, but it is **not validated**. Its measured input is inconsistent
  (see *Deviations*). The lead decided this in the brief's row-7 addendum (commit `878cc32`, 2026-11-15 23:09 UTC),
  before any S3, S3′, S4 or S5 run.
- **The rule is not changed.** `HikariAdvisor` and the other four classes are untouched. **S3 still misses**: the
  unchanged rule recommended ST-REC-HIKARI-001 INCREASE 10 → 13 in 11 of 11 evaluated polls. The known limitation
  stays.

**What ran.**

- All 18 pre-registered runs completed: 6 scenarios × 3 seeds, 16 polls each, no failed poll. The aborted S2 run
  described below is a 19th, incomplete run and is not evaluated.
- Every run used the same PetClinic jar. The starter's autoconfigure jar inside it has SHA-256 `249646af…131b`
  (identical in all 18 `environment.txt`), built from `86357dd`.
- `run.sh` and `LoadDriver.java` are unchanged between the pre-registration commit `6f1d188` and the last run.
  `environment.txt` records a different `spring_tune_commit` per run only because each run's raw data was committed
  as it finished.

Raw data is in [`petclinic/2026-11-15-runs/`](petclinic/2026-11-15-runs/), laid out like the 2026-11-09 runs. Each
poll also carries a `hold` object with the extra meters.

### Per run (evaluated polls only)

| Scenario | Run | Seed | Evaluated polls | Unchanged rule | P_max | P_max: share of waiting window polls with MAX > 0.5 s | P_share | P_share: window share > 500 ms |
|---|---|---|---|---|---|---|---|---|
| S1 | 1 / 2 / 3 | 6100n | 3 / 3 / 3 | none | none | n/a | none | n/a |
| S2 | 1 | 62001 | 3 | HIKARI-001 INCREASE 2 → 3 | INCREASE 2 → 3 | 0%, 0%, 0% | INCREASE 2 → 3 | 1.56%, −0.30%, −0.37% |
| S2 | 2 | 62002 | 4 | HIKARI-001 INCREASE 2 → 3 | INCREASE 2 → 3 | 33%, 25%, 22%, 11% | INCREASE 2 → 3 | −3.32%, −1.01%, 0.93%, −1.08% |
| S2 | 3 | 62003 | 3 | HIKARI-001 INCREASE 2 → 3 | INCREASE 2 → 3 | 0%, 0%, 0% | INCREASE 2 → 3 | 0.00%, 0.00%, 0.00% |
| S3 | 1 | 63001 | 4 | HIKARI-001 INCREASE 10 → 13 | HOLD | 100% ×4 | HOLD | 5.05%, 5.11%, 5.17%, 5.14% |
| S3 | 2 | 63002 | 4 | HIKARI-001 INCREASE 10 → 13 | HOLD | 100% ×4 | HOLD | 13.80%, 12.48%, 12.08%, 7.05% |
| S3 | 3 | 63003 | 3 | HIKARI-001 INCREASE 10 → 13 | HOLD | 100% ×3 | HOLD | 5.20%, 5.30%, 5.30% |
| S3′ | 1 | 63101 | 3 | INCREASE 10 → 13 in 2 polls, none in 1 | HOLD in those 2 | 100%, 100% | HOLD in those 2 | 7.89%, 7.86% |
| S3′ | 2 | 63102 | 3 | none | none | n/a | none | n/a |
| S3′ | 3 | 63103 | 3 | none in 2 polls, INCREASE 10 → 13 in 1 | HOLD in that 1 | 100% | HOLD in that 1 | 10.41% |
| S4 | 1 / 2 / 3 | 6400n | 3 / 3 / 3 | HIKARI-003 DECREASE 10 → 2 | same | n/a | same | n/a |
| S5 | 1 | 65001 | 3 | HIKARI-001 INCREASE 2 → 3 | **HOLD 2 → 2** | 100%, 100%, 100% | INCREASE 2 → 3 | 0.00%, 0.00%, 0.00% |
| S5 | 2 | 65002 | 3 | HIKARI-001 INCREASE 2 → 3 | **HOLD 2 → 2** | 100%, 100%, 100% | INCREASE 2 → 3 | 0.00%, 0.00%, 0.00% |
| S5 | 3 | 65003 | 3 | HIKARI-001 INCREASE 2 → 3 | **HOLD 2 → 2** | 100%, 100%, 100% | INCREASE 2 → 3 | 0.00%, 0.00%, 0.00% |

HOLD here means ST-REC-HIKARI-002 HOLD. Every window had 8 to 11 polls, so no candidate fell back.

### Against the bar

| Bar item | Unchanged rule | P_max | P_share |
|---|---|---|---|
| S3: no INCREASE, 3 of 3 | fail (0 of 3) | pass (3 of 3) | pass (3 of 3) |
| S3′: no INCREASE, 3 of 3 | fail (1 of 3) | pass (3 of 3) | pass (3 of 3) |
| S2: INCREASE 2 → 3 in every evaluated poll, 3 of 3 | pass | pass | pass |
| S5: INCREASE 2 → 3 in every evaluated poll, 3 of 3 | pass | **fail (0 of 3)** | pass |
| S1: no recommendation, 3 of 3 | pass | pass | pass |
| S4: unchanged, 3 of 3 | (DECREASE 10 → 2 as registered, 3 of 3) | pass | pass |
| Scenario part of the bar | — | **fail** | pass on paper; **not validated** |
| Tests part of the bar | — | not reached | not run (no implementation) |

**Reproduce:** `python3 -I scripts/petclinic/evaluate-hold.py docs/benchmarks/petclinic/2026-11-15-runs --polls`
from the repository root. Without the argument it reads these runs; `--polls` adds every evaluated poll with the
candidates' inputs. To collect new runs, follow petclinic.md's reproduce block, with `HOLD_CHECK=1 S2_RATE=1200`
and the scenarios `S1 S2 S3 S3P S4 S5`.

### What the unchanged rule did

- **S1**: no recommendation in 9 of 9 evaluated polls, as registered and as on 2026-11-09.
- **S2**: ST-REC-HIKARI-001 INCREASE 2 → 3 in 10 of 10, as registered and as on 2026-11-09. This time every
  evaluated poll fell within the load, because the polls are scheduled on the wall clock.
  - Evidence: `samplesWithWaitingThreads` 84–100%, mean hold "1 ms", mean acquire 5–74 ms.
  - `system.cpu.usage` was 0.85–0.87 in the evaluated polls, and the driver skipped 13–29% of scheduled requests
    at its in-flight cap. The CPU caveat of petclinic.md still applies.
- **S3: still the miss.** ST-REC-HIKARI-001 INCREASE 10 → 13 in 11 of 11.
  - Evidence: waiting 83–87%, mean hold 141–146 ms, mean acquire 761–817 ms.
- **S3′**: INCREASE 10 → 13 in 3 of 9 evaluated polls (run 1: 2 of 3, run 3: 1 of 3), no recommendation in the
  other 6. Run 2 gave none at all.
  - When it fired: waiting 81–97%, mean hold 139–141 ms, mean acquire 463–469 ms.
  - So S3′ catches the miss in 2 of 3 runs. In the quiet polls the window was presumably below the 80% waiting
    threshold. That share is not published when nothing fires, so it is not shown here.
- **S4**: ST-REC-HIKARI-003 DECREASE 10 → 2 in 9 of 9, as registered.
- **S5**: ST-REC-HIKARI-001 INCREASE 2 → 3 in 9 of 9, as predicted.
  - Evidence: waiting 90–97%, mean hold "1 ms", mean acquire 25–50 ms.
  - All 7 slow edits of each run answered 302, and the usage `MAX` in the evaluated polls was 3.01–3.02 s.

**The acquire/hold ratio on the new runs** confirms the step-0 rejection. From the evidence above:

| Scenario | acquire / hold |
|---|---|
| S2 run 2 | 5–6 (hold rounded to "1 ms") |
| S3 | 5.2–5.7 |
| S3′ | 3.3–3.4 |

So S2, where the pool really is the narrow point, gives the same ratio as the lock.

### Why each candidate came out as it did

- **P_max** did what it was built for in S3 and S3′: the usage `MAX` was 2.85–2.86 s and 1.91–1.92 s in every
  evaluated poll. But it fails S5, as pre-registered.
  - One 3-s checkout a minute keeps the decaying `MAX` above 0.5 s all the time. The `MAX` lasted 2–2.5 minutes in
    the plumbing check, and was 3.01–3.02 s in every evaluated S5 poll.
  - So P_max blocks a raise the scenario says is right. This is the skeptic's objection of row 7, now measured.
- **P_share** separated the scenarios in the table: 5–14% in S3, 8–10% in S3′, 0.00% in S5, and −3.3% to 1.6% in
  S2. But its input is not what it claims to be (next section). Negative shares and the S3 run-2 range of 7–14%
  are artefacts of reading two gauges at two moments. A rule fitted to these numbers would be fitted to that noise.
  - An implementation would read one histogram snapshot (`Timer.takeSnapshot()`), which is not the estimator
    evaluated here.
  - Per the row-7 addendum it is reported as **not validated** and not implemented.

### Deviations from the pre-registration

- **Aborted S2 run 2, re-run with the same seed.** The first S2 run 2 (seed 62002, first poll 2026-11-15 14:16:32
  UTC) was killed by a worker restart after 10 polls (last at t = 270 s). No `driver-summary.json` was written.
  - It is kept as [`s2/run-2-aborted/`](petclinic/2026-11-15-runs/s2/run-2-aborted/), with a `NOTE.txt`, and not
    evaluated.
  - S2 run 2 was re-run from a fresh PostgreSQL and application with the same seed (`s2/run-2`, 23:07–23:15 UTC).
    The remaining runs followed in order.
  - A gap of about 8.8 hours separates S2 run 1 from the rest. The machine and the jar were the same (hash above).
- **A 70-second plumbing run after the pre-registration commit.** At 13:44–13:45 UTC, after `6f1d188`, `run.sh run
  S5 1` ran once with `LOAD_SECONDS=70 S2_RATE=50` and output outside the repository, to check the new poll code.
  - It used seed 65001, at 50 req/s instead of 1,200, for 70 s instead of 450, so no window was covered.
  - Its output was discarded. It is not one of the 18 runs, and nothing in the results comes from it.
- **The two bucket gauges are inconsistent.** Each poll reads `le:0.5` and `le:1h` as two separate HTTP requests
  for two gauges of a rotating, decaying histogram. A rotation between the two reads gives counts from different
  moments. Counted over all 288 polls of the 18 runs (`evaluate-hold.py`'s consistency table):
  - **23 polls have `le:0.5` > `le:1h`**, impossible within one snapshot: S1 6, S2 10, S3 6, S3′ 1, S4 0, S5 0.
  - **16 polls show checkouts above 500 ms while the usage `MAX` was ≤ 0.5 s**: S1 6, S2 10, others 0.
  - This was found after the runs began (S2 run 1). The poll code was not changed mid-series, to keep the inputs
    fixed. It invalidates P_share's measured share, as described above. The pre-registration did not foresee it.
- **Where failed polls fall.** The pre-registration counts a failed poll as "no recommendation". A failed read has
  no `generatedAt`, so `evaluate-hold.py` places it by its slot on the wall-clock schedule: evaluated when
  `elapsed_seconds` is in [360, 450]. **No poll failed in any of the 18 runs**, so this rule decided nothing.
- **Wall-clock polls.** As registered, these replace petclinic.md's sleep-after-poll loop. No slot was skipped:
  every run has 16 polls.

### Step 4

**Not done, by the pre-registration.** P_max does not meet the bar (S5 fails 0 of 3). P_share is not validated,
and per the row-7 addendum only P_max could change the rule in this iteration. The tests part of the bar is
therefore not reached, and no main code was changed. With the rule unchanged, the pre-registered re-run of S3 on a
changed starter does not apply. The unchanged rule's S3 result above (INCREASE 10 → 13 in 11 of 11) is the answer
to "does S3 still miss": **yes**.

What a next attempt would need, as a note, not a result:

- P_max with a time-weighted rather than decaying max, which is not available from Micrometer's timer.
- Or P_share read from one histogram snapshot inside the starter, pre-registered and evaluated anew on runs that
  record that same estimator.

### Limitations

- Everything in petclinic.md's limitations applies.
- The candidates were evaluated on 30-s polls against a 5-minute window, while the starter samples every 10 s.
- The S3′ duty cycle was chosen once and not varied.
- Three seeds per scenario.
- No latency, throughput or "faster" figure is reported. The driver records counts only.
