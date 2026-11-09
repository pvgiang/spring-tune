# Load tests: apply mode against a real PostgreSQL

`springtune-loadtests` proves the guarded apply mode ([starter](starter.md#apply-mode-experimental)) against a
**real PostgreSQL** with real Spring Boot replicas, under an open-loop load, with assertions the database itself
enforces. It answers bars 2–4 of the pre-registered apply bar ([iteration 4 brief](delivery/iteration-4-brief.md) §2)
and the challenge-log rows 2–8, 11–13 and 17.

## What is asserted, and what is not

Only **counts, verdict and outcome enums, refusal codes, SQLSTATEs, server-log lines and `pg_stat_activity`**.
Never an elapsed time, a latency or a mean: CPU is shared by the load generator, the replicas and (locally) the
database, so timing assertions would test the machine. Waiting is Awaitility polling with generous ceilings
(90 s for a recommendation, 120 s for a verification); nothing is retried, neither a request nor a test
(`rerunFailingTestsCount=0`, no retry loops).

The database is the referee:

- `pg_stat_activity`, sampled every 50 ms by a superuser probe: sessions per `application_name` in every state (an
  idle pooled connection is a session too).
- The server log between two `RAISE LOG` markers per test: `too many clients already` / `remaining connection slots
  are reserved` (server limit, SQLSTATE 53300), `too many connections for role` (role limit, 53300), and with
  `log_connections=on` every `connection authorized: user=<role>`.
- Client SQLSTATEs counted by the load generator (HikariCP's timeout carries the last connect failure's state).

## Setup

| | |
|---|---|
| Server | `max_connections=23`, `superuser_reserved_connections=3` (default), `log_connections=on`: **application roles may open 20 sessions**. (With `max_connections=20` they could open only 17, and the brief's own budget of 20 would be refused by `BUDGET_EXCEEDS_SERVER`, correctly.) The superuser probe's session counts against those 20. |
| Backends | `-Dspringtune.it.postgres.url=jdbc:postgresql://host:port/postgres` (+ `.user`, `.password`, superuser) and `-Dspringtune.it.postgres.log=<server log>`: an external server. Otherwise Testcontainers `postgres:17-alpine` with the same settings; the log is the container output. |
| Isolation | Each test creates its own database and one role per replica, dropped afterwards. No server setting is changed. |
| Replicas | Real Spring Boot servlet applications (`SpringApplicationBuilder`, random port), starter + actuator + Spring Security (HTTP basic, role `TUNER`), each with its own Hikari pool `app` (4/4, fixed size), `ApplicationName=replica-N` and its own role. All in one JVM. |
| Operator | The test, through `GET /actuator/tuning` and `POST /actuator/tuning` with `expectedCurrent` and `expectedTarget`, as a human would. Simultaneous applies are released by a `CyclicBarrier`. |
| Load | `OpenLoopJdbcLoad`: fixed arrival rate (200/s per replica) with seeded jitter (each interval uniform in [0.5, 1.5] of the mean; the seed is logged, replay with `-Dspringtune.it.seed`), 12 workers, a bounded queue of 100 that counts drops. A finished worker returns to the idle pool for 1 ms before it takes the next request, so a returning thread cannot re-borrow its own connection through HikariCP's thread-local fast path ahead of a waiter. The load drives each replica's `DataSource` directly (the system under test is the pool), not its HTTP port. |
| Hold time | `pg_sleep` in the database. "Slower with more sessions" is decided by the server: each statement counts the active sessions of its own `application_name` in `pg_stat_activity` and sleeps 30 ms up to 4 of them, 60 ms above. |

### Compressed timings

| Property | Load tests | Default |
|---|---|---|
| `springtune.sampling.interval` | 1 s | 10 s |
| `springtune.hikari.window` | 15 s | 5 m |
| `springtune.apply.baseline` | 10 s | 5 m |
| `springtune.apply.warmup` | 3 s | 30 s |
| `springtune.apply.verification-window` | 20 s | 5 m |
| `springtune.apply.cooldown` | 10 s | 15 m |
| `springtune.apply.cooldown-after-revert` | 10 m | 60 m |
| Budget | `database-max-connections=20`, `safety-factor=0.8` (16 usable), `max-replicas=3` → **cap 5** | |

## The suites

### `PostgresApplyLoadTest` (one replica)

| Test | Proves |
|---|---|
| `raiseIsConfirmedAndTheServerNeverSeesMoreSessionsThanTheNewMaximum` | Saturated 4/4 pool, 30 ms holds → `ST-REC-HIKARI-001` INCREASE 4 → 5, applied, **CONFIRMED** (R1–R3 OK), `capacityObtained=true`. `pg_stat_activity` ≤ 4 sessions before and exactly 5 at most after; no 53300, no client error. |
| `slowSqlIsHeldAndApplyIsRefused` | 600 ms `pg_sleep` (> `slow-usage` 500 ms) → `ST-REC-HIKARI-002` **HOLD**; applying it → 409 `RECOMMENDATION_IS_HOLD`, applying 001 anyway → 409 `NO_CURRENT_RECOMMENDATION`; pool still 4, server never above 4. |
| `raiseThatSlowsEverySessionIsRevertedOnceAndNotRetried` | Bar 4. The server slows every statement once a 5th session is active → R2 **REGRESSED → REVERTED** to 4/4. The pool stays saturated, so the operator tries again: 409 **`COOLDOWN`**, still `COOLDOWN` 25 s later (longer than the normal cooldown); exactly **one** change executed: no oscillation. |

### `BudgetLoadTest` (bar 2 and its assumptions, brief rows 5–6)

Every replica starts at 4/4 and is saturated, so each one is recommended 4 → 5 = the cap.

| Test | Proves |
|---|---|
| `threeReplicasRaisingAtOnceStayWithinTheBudget` | 3 replicas apply at the same instant: all accepted, ≥ 2 (in practice 3) CONFIRMED, each with `capacityObtained=true` (asserted for every CONFIRMED entry in the four "raise at once" tests); per replica ≤ 5 sessions, service ≤ 16, all client backends ≤ 20; **zero** `too many clients` lines and **zero** client 53300. After the cooldown, further applies are refused (`RECOMMENDATION_IS_HOLD`: at the cap). |
| `raiseWhileHikariReplacesConnections` | Same with `maxLifetime=30s` (HikariCP's minimum), so every connection is retired and replaced during the verification window. Same invariants; the server log proves the replacement (35 authorizations for at most 15 open sessions). HikariCP decrements its count before the physical close completes, so a brief overshoot per replica was a real possibility: none was observed. |
| `oneReplicaRevertsWhileTwoRaise` | replica-3's database slows down with a 5th session: replica-1/2 CONFIRMED, replica-3 REVERTED, same invariants; replica-3's further apply → `COOLDOWN`. |
| `anotherServiceHoldsConnectionsDuringTheRaise` | Another service (own role, fixed pool of 3, busy) on the same server, invisible to the budget. 15 + 3 + the probe = 19 ≤ 20: no refusal. The safety factor's headroom (20 − 16 = 4) is exactly what other clients may use. |
| `surgeReplicaNotCountedInMaxReplicasBreachesTheBudget` | **Negative control.** A 4th (surge) replica with `max-replicas=3`: each cap is still 5, all four raise. The probe **must** see the breach: service sessions 19 > 16, and the server refused the last connection attempts (`remaining connection slots are reserved` in the log). This proves the probe and the log scan see a breach when there is one. **R2 regression test:** every replica's `capacityObtained` must equal "the server saw 5 sessions of it" (`pg_stat_activity`), and at least one replica must be CONFIRMED with `capacityObtained=false` and "never exceeded 4 connections; target 5". |
| `surgeReplicaCountedInMaxReplicasStaysWithinTheBudget` | The same 4 replicas with `max-replicas=4`: cap 4, every apply refused (`RECOMMENDATION_IS_HOLD`, "already at its connection budget cap"), service ≤ 16, no refusal by the server. |
| `serverLogAndClientSeeTheServersLimit` | Negative control for the log scan and the SQLSTATE count: one role opens connections until the server refuses: SQLSTATE 53300 on the 21st (20 opened), and the server log has the line. |

### `DatabaseGuardLoadTest` (brief rows 7–8, bar 3)

| Test | Proves |
|---|---|
| `budgetLargerThanTheServerIsRefused` | `database-max-connections=100` against a server that accepts 20: the cap is 26, so the INCREASE 4 → 5 is "within budget", but the apply plan says `server limit: PostgreSQL max_connections 23 - superuser_reserved_connections 3 - reserved_connections 0 = 20; database-max-connections 100 > 20: apply mode refuses to raise the pool (BUDGET_EXCEEDS_SERVER)` and the apply is **409 `BUDGET_EXCEEDS_SERVER`**. |
| `databaseRefusingConnectionsIsNotAnswerWithALargerPool` | The failing database: the replica's role gets `CONNECTION LIMIT 2` and its 4 sessions are terminated. HikariCP re-opens 2, every further attempt is refused (`too many connections for role` in the log); threads wait while holds stay at 30 ms. **Before this iteration, `ST-REC-HIKARI-001` recommended INCREASE here**, the worst move (mutation M6 below shows it). Now `ST-REC-HIKARI-004` **HOLD** ("the pool could not open the connections it already has room for"), both applies refused, zero changes executed, and for a further 20 s no INCREASE appears. |

### `ServerLimitHangLoadTest` (iteration 6, brief rows 8–9; iteration-5 review R3)

The replica's pool reaches PostgreSQL through an in-JVM TCP relay (`TcpRelay`). Armed, the relay **stops
forwarding** the first connection that sends the server-limit query: no answer, no error, both sockets left open,
as on a half-open TCP connection (pgjdbc's default `socketTimeout=0` would wait for the kernel, ~15 min). The relay
keeps reading what the client sends, so it sees the moment the pool closes that socket. `connectionTimeout=3000`,
which is also the bound of the read (the pool's `connectionTimeout`, at most 30 s).

| Test | Proves |
|---|---|
| `hungServerLimitReadIsRefusedWithinTheBoundReleasesItsConnectionAndTheNextRaiseSucceeds` | Saturated 4/4 pool, `ST-REC-HIKARI-001` INCREASE 4 → 5; the limit read's connection is frozen. The apply is **409 `SERVER_LIMIT_UNKNOWN`** and answered within bound + 5 s (a ceiling, not a measurement); the pool stays at 4. The read itself ends too: `setNetworkTimeout(3000)` on the borrowed connection makes pgjdbc's socket read fail (`An I/O error occurred while sending to the backend.`, SQLSTATE 08006), HikariCP **evicts** that connection (the relay sees it closed within bound + 5 s) and refills the pool to **4** connections; the apply plan records the failed re-read. After `resume()` the next apply re-reads the limit and is **200** "Applied 4 -> 5". |

Measured (2026-11-09, PostgreSQL 16, two runs: alone, then in the full suite): the hung apply answered after
3191 / 3256 ms; the frozen connection was closed by the pool 3006 / 3004 ms after its query; total connections back
to 4; the raise after resume applied (200) both times.
Mutation M9 below removes the network timeout.

The other half of row 8 (a **decrease** never reads the limit, fail closed) is proven by unit tests, since a
decrease needs a window of idle samples the suite does not wait for: `ChangeControllerTest`
(`decreaseIsAppliedWithoutReadingTheServerLimitEvenWhenTheReadWouldNeverReturn`: a reader that never returns, 0
reads, applied; `recommendationFlippingFromDecreaseToRaiseNeverRaisesWithoutAFreshLimit`) and `ApplyGuardTest`
(`readSkippedForADecreaseRefusesAnyRaise`); `ServerLimitNetworkTimeoutTest` shows HikariCP restoring the pool's
own network timeout when the connection is returned.

## Product changes found by these tests

1. **`BUDGET_EXCEEDS_SERVER` (409)** (row 7). Before raising a Hikari pool, the configured
   `database-max-connections` is compared with the server's limit for application roles, read through the pool
   (since iteration 5: **re-read for every apply attempt**, bounded by `connectionTimeout`; a failed re-read refuses
   the raise with `SERVER_LIMIT_UNKNOWN`; since iteration 6 only for an apply that raises the pool, with the same
   bound as a network timeout on the read's connection)
   (`ServerConnectionLimit`: PostgreSQL `max_connections − superuser_reserved_connections − reserved_connections`,
   MySQL/MariaDB `@@max_connections`, any other database not checked, which the apply plan says). The read runs on a
   short-lived thread with one pooled connection, so the sampler never waits behind a saturated pool.
2. **`ST-REC-HIKARI-004` HOLD** (row 8). Threads waiting while the pool holds fewer connections than its maximum in
   ≥ 50% of the window's samples, or acquire timeouts while it does, means the database refuses or fails new
   connections. Checked before 001/002. "Fewer connections" is HikariCP's `getTotalConnections()` (one read of
   the pool's connection list), not `active + idle`: those are two separate scans, and a connection handed from
   one thread to the next between them is in neither. Measured on a healthy, full 4/4 pool with 1 ms holds and
   12 threads: 7 of 1955 readings had `active + idle = 3` while threads waited (0 with `total`), so with acquire
   timeouts in the window a single such reading turned a pool that is simply too small into a 004 HOLD (review
   finding, fixed; `AdvisorTest.connectionInHandoffIsNotAStarvedPool`).

Findings documented, not changed:

- After a revert of a fixed-size pool (min = max) the extra connection stays open until `maxLifetime`: the test
  records 5 open connections after 4 → 5 → 4 (HikariCP never retires idle connections of a fixed pool). It stays
  within the cap the budget already assumes.
- In the surge test one replica was CONFIRMED although the server refused its 5th connection: verification checks
  that nothing got worse, not that the new capacity was obtained. `ST-REC-HIKARI-004` holds any further raise of that
  pool. **Iteration 5 (R2):** still CONFIRMED (no revert, by decision), but the entry now says
  `capacityObtained=false` ("the pool never exceeded 4 connections; target 5, although threads waited for a
  connection (connection creation refused or failing?)"), the `POST`/snapshot JSON shows it and
  `springtune.changes{capacity_obtained="false"}` counts it; the surge test asserts it against `pg_stat_activity`.
- **Iteration 6 (R3 follow-up):** a server-limit read that hangs no longer pins its connection: the read is
  bounded by a network timeout on the borrowed connection (`ServerLimitHangLoadTest`), and a decrease no longer
  reads the limit at all.
- **Iteration 5 (R3)** is not exercised here: lowering `max_connections` needs a server restart, which the shared
  server of the suite cannot do per test. `ChangeControllerTest` and `ServerLimitRecheckTest` cover the re-read;
  `budgetLargerThanTheServerIsRefused` runs it against the real server (the refusal now comes from the per-apply
  re-read).

## Measured connection counts

From three local passes of every suite (PostgreSQL 16.15, 4 vCPUs, all replicas in one JVM). Maxima of the 50 ms
`pg_stat_activity` readings; "53300" is server-log lines plus client SQLSTATEs.

All 36 test runs (12 tests × 3 passes) were green, with no retries. Iteration 6 (2026-11-09, PostgreSQL 16, one
pass): all **13** tests green (the 12 plus `ServerLimitHangLoadTest`), no retries. Iteration 5 (2026-11-09, PostgreSQL 16, one pass of all 12 tests, green): surge `max-replicas=3` again 5, 5, 5, 4 per replica, service max 19 (509 of 843 readings above 16), 14 refusal lines; replica-4 CONFIRMED with `capacityObtained=false`; every other CONFIRMED raise in the suite `capacityObtained=true` (15 of 15), and both REVERTED raises `true` too (they got their 5th connection; it made things slower).

| Test | Per replica max | Service max (readings > 16) | All client backends max | 53300 (log / client) | Outcomes (all 3 passes) |
|---|---|---|---|---|---|
| raise confirmed | 4 before, 5 after | – | – | 0 / 0 | CONFIRMED |
| slow SQL held | 4 | – | – | 0 / 0 | 002 HOLD; `RECOMMENDATION_IS_HOLD`, `NO_CURRENT_RECOMMENDATION` |
| revert, no oscillation | 5 | – | – | 0 / 0 | REVERTED (R2); `COOLDOWN`, `COOLDOWN`; 1 change executed; 5 connections still open at max 4 |
| 3 replicas at once | 5, 5, 5 | 15 (0 of ~1050) | 16 | 0 / 0 | 3 × CONFIRMED; further: 3 × `RECOMMENDATION_IS_HOLD` |
| short `maxLifetime` | 5, 5, 5 | 15 (0 of ~1020) | 16 | 0 / 0 | 3 × CONFIRMED; 31–36 authorized connections for ≤ 15 open |
| one reverts, two raise | 5, 5, 5 | 15 (0 of ~1030) | 16 | 0 / 0 | CONFIRMED, CONFIRMED, REVERTED; further: HOLD, HOLD, `COOLDOWN` |
| other service (K = 3) | 5, 5, 5; other 3 | 15 (0 of ~1050) | 19–20 | 0 / 0 | 3 × CONFIRMED |
| surge, `max-replicas=3` | 5, 5, 5, 4 | **19 (≈ 510 of ≈ 855)** | 20 | **14 / 0** | 4 × applied, 4 × CONFIRMED; one replica's 5th connection refused by the server, and (iteration 5) that replica reports `capacityObtained=false`, the other three `true` |
| surge, `max-replicas=4` | 4, 4, 4, 4 | 16 (0 of ~355) | 17 | 0 / 0 | 4 × `RECOMMENDATION_IS_HOLD` |
| log sees the limit | – | – | 20 | 1 / refused with 53300 | 20 opened, the 21st refused |
| budget exceeds server | 4 | – | – | – | `BUDGET_EXCEEDS_SERVER` |
| failing database | ≤ 4 (2 after the failure) | – | – | 16 role-limit lines | only 004 HOLD seen; 0 changes executed |

In the surge case the client SQLSTATE count is 0 because the refused attempts are HikariCP's background fills, not
requests; the server log has them. The "other service" run touched exactly 20 client backends once (probe plus a
harness admin session), so the headroom really is used up at K = 3.

## Mutation proof

Each mutation was applied to the product code, the matching test run against the real PostgreSQL, and the code
restored. Every one was caught (2026-11-05, PostgreSQL 16.15):

| # | Mutation | Test | How it failed |
|---|---|---|---|
| M1 | `ConnectionBudget.perInstanceCap`: `floor` → `ceil` (cap 6 instead of 5) | `BudgetLoadTest.threeReplicasRaisingAtOnceStayWithinTheBudget` | after the cooldown all three further applies were **200** (5 → 6); the server saw 6 sessions per replica and 18 for the service (> 16) |
| M2 | `HikariAdvisor`: slow-usage check removed | `PostgresApplyLoadTest.slowSqlIsHeldAndApplyIsRefused` | 600 ms holds got `ST-REC-HIKARI-001` **INCREASE** 4 → 5 instead of 002 HOLD |
| M3 | `Verification`: rule R2 (hold-time regression) removed | `PostgresApplyLoadTest.raiseThatSlowsEverySessionIsRevertedOnceAndNotRetried` | the raise that doubled every hold time was **CONFIRMED** instead of REVERTED |
| M4 | `ChangeController`: no cooldown after a revert (`cooldownUntil = now`; the property itself rejects 0) | same test | REVERTED, then the second apply was **200** "Applied 4 -> 5": oscillation |
| M5 | `ApplyGuard`: `BUDGET_EXCEEDS_SERVER` check removed | `DatabaseGuardLoadTest.budgetLargerThanTheServerIsRefused` | the apply was accepted (200) with a budget of 100 on a 20-connection server |
| M6 | `HikariAdvisor`: `ST-REC-HIKARI-004` removed (the product before this iteration) | `DatabaseGuardLoadTest.databaseRefusingConnectionsIsNotAnswerWithALargerPool` | the failing database got `ST-REC-HIKARI-001` **INCREASE** 4 → 5 ("connections were returned quickly (30 ms average hold)") |

An independent review (2026-11-06) re-ran M5 and M6 (both caught as above) and added two mutations the load suite
is **not** meant to catch, to show where the unit tests carry the proof:

| # | Mutation | Load suite | Unit tests |
|---|---|---|---|
| M7 | `HikariPoolWriter.plan`: never moves `minimumIdle` (a fixed 4/4 pool becomes 5/4 dynamic) | **survives** (`PostgresApplyLoadTest`, `threeReplicasRaisingAtOnce...` green: HikariCP grows on demand anyway) | caught: `KnobWriterTest`, `HikariApplyIntegrationTest` |
| M8 | `ChangeController`: in-flight flag always false (two changes at once) | not exercised (one knob per replica, no apply during VERIFYING) | caught: `ChangeControllerTest` (2 tests) |

Iteration 6 (2026-11-09, PostgreSQL 16):

| # | Mutation | Test | How it failed |
|---|---|---|---|
| M9 | `HikariComponent.readServerLimit`: no `setNetworkTimeout` on the borrowed connection (the 0.4.1 behaviour) | `ServerLimitHangLoadTest` | the apply was still refused (409 `SERVER_LIMIT_UNKNOWN`, the caller's wait is bounded anyway), but the frozen connection was **never released**: "the pool gives the frozen connection up" timed out; also caught by `ServerLimitNetworkTimeoutTest` (2 tests) |
| M10 | `ApplyGuard`: no refusal for `SKIPPED_FOR_DECREASE` | load suite: not exercised | caught: `ApplyGuardTest.readSkippedForADecreaseRefusesAnyRaise`; together with M11, `ChangeControllerTest.recommendationFlippingFromDecreaseToRaise...` sees a raise applied without a read |
| M11 | `ChangeController.apply`: re-evaluates the recommendations instead of using the evaluation the read decision was made on | not exercised | caught: `ChangeControllerTest` (2 tests); with the guard intact the flipped raise is refused `SERVER_LIMIT_UNKNOWN` (fail closed) |

The surge test (`surgeReplicaNotCountedInMaxReplicasBreachesTheBudget`) is the standing negative control for the
probe and `serverLogAndClientSeeTheServersLimit` for the log scan: both must observe a breach on every run.

## Running them

```bash
# External server (needs max_connections=23, log_connections=on, a superuser):
mvn -B -ntp verify -pl springtune-loadtests -am -DskipITs -Dinvoker.skip \
  -Dspringtune.it.postgres.url=jdbc:postgresql://localhost:55432/postgres \
  -Dspringtune.it.postgres.log=/path/to/postgresql.log
# Testcontainers (CI): fails instead of skipping when Docker is missing
mvn -B -ntp verify -pl springtune-loadtests -am -DskipITs -Dinvoker.skip -Dspringtune.it.requireDocker=true
```

Without one of these flags (or `-Dspringtune.it.load=true`) the module compiles its tests and runs none, so the
normal `mvn verify` and the Spring Boot 3.5 build stay fast and need no Docker. With a flag but neither Docker nor
an external URL the suites are skipped, unless `requireDocker=true`. Results land in `target/loadtests/`: one
`<test>.txt` with every measured count and the seed, and `<test>.server.log` with that test's server-log slice.

CI: the `loadtests` job (one pass per push/PR) and `nightly.yml` (the suite 3 times in parallel jobs and
`HikariApplyIntegrationTest` 30 times, every failure counted, no retries). Per the brief (row 11) the suite gates
release tags only after 20 consecutive green nightlies.

## Limits

- **In-JVM replicas** share CPU, heap and GC with each other and with the load generator; they are separate Spring
  contexts and pools, not separate machines or networks.
- **Compressed windows** (seconds instead of minutes). The rules are the same functions; longer windows only add
  samples. Noisy production traffic over 5 minutes is not reproduced.
- **Boot 4.1 only.** The suites are skipped on Spring Boot 3.5 (the module still compiles there); the unit and H2
  tests cover 3.5.
- **The Testcontainers path is proven only in CI.** Locally (no Docker) every run used an external PostgreSQL 16;
  CI uses `postgres:17-alpine`.
- The 3-replica "never exceed" result is partly by construction (`floor(16 / 3) × 3 = 15`); the assumption attacks
  are what makes it informative, and the surge control shows the probe would have seen a breach.
- A 50 ms probe can miss a shorter excursion; a refused connection cannot be missed, because the server logs it.
- MySQL/MariaDB server-limit reads are implemented but not tested against a real server.
