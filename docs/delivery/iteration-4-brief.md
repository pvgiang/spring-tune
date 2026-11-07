# Iteration 4: Delivery brief (load proof on real PostgreSQL, first Maven Central release)

> Follows [iteration 3](iteration-3-brief.md). Input: [roadmap](../roadmap.md) step 2 and the
> [plan](../business-and-implementation-plan.md) §16 definition of done ("zero unsafe changes in load tests") and §20.

## 1. Goal

> Prove the guarded apply mode against a **real PostgreSQL** with assertions the database itself enforces, close
> the remaining end-to-end gaps, publish overhead numbers, and ship it all as **0.4.0, the first release on Maven
> Central**, with a smoke job that proves a clean project can install it.

## 2. Pre-registered bar for leaving "experimental" (PO/SRE, recorded before any load-test code)

Iteration 3's skeptic set this bar for apply mode; it was never written down (the roadmap cited a non-existent
"brief AC-12"). Recorded verbatim here, before implementation:

> Apply ships as non-experimental only with **all six**: (1) a design partner running advise in production;
> (2) a load test in which 3 replicas raising at once never exceed the budget; (3) a failing-DB scenario in which
> apply is refused; (4) one revert with no oscillation; (5) executor ordering tests in both directions; (6) the
> write operation absent without Spring Security plus explicit access.

Bars 2–6 are this iteration's to prove. Bar 1 cannot be met with zero users, so **apply stays experimental in 0.4.0**.

## 3. Challenge log

Eng architect designed, PO/SRE skeptic attacked; the user's instruction settles the release question.

| # | Raised by | Challenge | Decision |
|---|---|---|---|
| 1 | PO/SRE | Don't bundle: release 0.4.0 to Central now, put the load proof in 0.5.0. Every week without Central delays §9 gate 1. | **User decision: combine.** One PR carries the test fix, this phase and the release notes; the release is **0.4.0**. To limit the delay, cut scope hard (rows 9–10) and keep every item verifiable in this sandbox. |
| 2 | Eng | No Docker here, so real-PostgreSQL suites could only be proven in CI. | **Found a real PostgreSQL 16 in the sandbox.** The suites run against an external server when `-Dspringtune.it.postgres.url` is set (local), else Testcontainers `postgres:17-alpine` (CI). Same tests, two backends. |
| 3 | PO/SRE | Load tests prove nothing if they assert on latency/means with CPU shared by clients, 3 contexts and the DB. | **No assertion on elapsed time or means.** Only counts, verdict enums, refusal codes, SQLSTATEs, server log lines and `pg_stat_activity`. |
| 4 | PO/SRE + Eng | Slowness must come from the database, not CPU; workers need think time (the HikariCP thread-local handoff flake). | Hold time via `pg_sleep`; "slower with more sessions" decided server-side from `pg_stat_activity`. **Open-loop** load generator (fixed arrival rate, seeded jitter, logged seed); a finished task returns its thread to an idle pool instead of re-borrowing. |
| 5 | PO/SRE | "3 replicas never exceed the budget" is true by construction of `floor(max × sf / replicas)`; a test of that only re-checks arithmetic. | Keep it (bar 2), but make the suite attack the **assumptions**: a surge 4th replica, another service holding connections, short `maxLifetime` (Hikari replacing connections while raising), and one replica reverting while others raise. The database enforces `max_connections`; an overshoot is SQLSTATE 53300 / `too many clients` in the server log, which sampling cannot miss. |
| 6 | PO/SRE | Surge pods (rolling update `maxSurge`) or an HPA above `max-replicas` break the cap silently. | Test both configurations: `max-replicas` excluding the surge pod **must** show the breach (documented, reproducible), and including it must not. Docs: "count surge pods in max-replicas". |
| 7 | PO/SRE | Configured `database-max-connections=100` against a real `max_connections=20` is a likely misconfiguration only a real DB can catch. | **Product change:** before raising a Hikari pool, read the server's limit (PostgreSQL `SHOW max_connections` minus `superuser_reserved_connections`; MySQL/MariaDB `@@max_connections`); refuse with `BUDGET_EXCEEDS_SERVER` when the configured budget is larger. Unknown database → no check, noted in the apply plan. |
| 8 | PO/SRE | Failing DB: refused/timed-out connects raise waiters while hold time stays low, so ST-REC-HIKARI-001 may recommend INCREASE, the worst move. | Test it on real PostgreSQL (role `CONNECTION LIMIT` / unreachable server). If an INCREASE appears, it is a **product fix**: connection-acquire timeouts or creation failures in the window → HOLD with a named reason. |
| 9 | Both | Re-run the corpus's Gradle projects through the plugin. | **Cut.** Only 2 of 20 corpus repos are Gradle (one Boot 2.0-era); add Gradle 8.5+ repos to the corpus first. |
| 10 | Both | Kafka listener concurrency / cache rules. | **Cut.** Zero `rule-request` issues; partition counts are invisible statically, so the FP risk is high. Revisit on the first request. |
| 11 | Eng vs PO/SRE | Load suite gating: Eng 3 repetitions per CI job; PO/SRE 20/20 nightly before it gates anything. | Both: the `loadtests` CI job runs on PRs/pushes (1 pass) and a nightly workflow runs 3 passes. **No retries anywhere** (no `rerunFailingTestsCount`, no retrying tests). It gates release tags only after 20 consecutive green nightlies; 0.4.0 notes say so. |
| 12 | Eng | Gatling or a plain Java generator? | Plain Java: the system under test is the JDBC pool; Gatling adds a second lifecycle and cannot tie assertions to `pg_stat_activity`. |
| 13 | Eng | Testcontainers is 2.0.5 in the Boot 4.1 BOM (`testcontainers-postgresql`) but 1.21.4 in Boot 3.5 (`postgresql`). | The loadtests module imports `testcontainers-bom` 2.0.5 itself, so the boot3 build resolves it. Load suites run on Boot 4.1 only (weakness, noted). |
| 14 | Eng | JMH overhead (plan §20). | Slim `springtune-benchmarks` module behind `-Pbenchmarks`, never published, plus a `workflow_dispatch` workflow. **Pre-registered gates:** sampler tick with 4 components < 1 ms; limiter filter adds < 10 µs per request. Numbers measured here and published with the machine noted. |
| 15 | Eng | Post-release smoke: a clean project must install from Central and the Plugin Portal. | `post-release-smoke.yml` (`workflow_dispatch(version)`): fresh local repository, Maven project with starter + limiter + `springtune:check`, Gradle 8.14.3/9.8.2 applying the plugin by id; retries for Central propagation. Fixtures runnable locally against a staged repository. |
| 16 | Eng | `docs/releases/v0.3.0.md` claims "first release on Maven Central"; it was GitHub-only. | Amended; 0.4.0 notes carry the first-Central framing and every known limit. |
| 17 | PO/SRE | `HikariApplyIntegrationTest` must be proven stable, not hoped stable. | Root cause fixed (think time); 30 consecutive local runs recorded below, and the nightly runs it again. |

### Review round (adversarial reviewers against the implementations)

| # | Raised by | Finding | Decision |
|---|---|---|---|
| R1 | Load reviewer | **`ST-REC-HIKARI-004` false HOLD** on a healthy, too-small pool: HikariCP counts active and idle in two scans, so a connection in handoff was counted in neither and the pool looked starved (reproduced on PostgreSQL 16: 7 of 1955 readings at 1 ms holds). One such reading with timeouts blocked a needed raise for a window. | **Fixed (production):** starvation uses `getTotalConnections()` (one read). Regression test `AdvisorTest.connectionInHandoffIsNotAStarvedPool` fails on the old check. |
| R2 | Load reviewer | A replica was CONFIRMED although the server refused its new connection: verification checks "nothing got worse", not that capacity was obtained. | Accepted, documented. 004 holds further raises and the budget already counts the slot; reverting on connect failures would act during exactly the outages where a revert helps least. |
| R3 | Load reviewer | When the server-limit read fails, `BUDGET_EXCEEDS_SERVER` is skipped (retried each tick); never re-read after success. | Accepted per row 7, documented; the apply plan says "not checked yet". |
| R4 | Load reviewer | The load suite cannot detect two mutations (`minimumIdle` not moved; two changes in flight): HikariCP grows on demand, and the suite never sends concurrent applies. | Accepted: `KnobWriterTest`, `HikariApplyIntegrationTest` and `ChangeControllerTest` kill them; listed in docs/loadtests.md. |
| R5 | E2E reviewer | `SamplerTickBenchmark` filled windows without advancing the clock, so the claimed steady state was false. | Fixed; re-measured 376 ± 17 µs (gate < 1 ms holds). |
| R6 | E2E reviewer | JMH exits 0 on a failed benchmark without `-foe true`, and `| tee` without pipefail hid it. | Fixed in `benchmarks.yml`. |
| R7 | E2E reviewer / lead | Latency assertions in new tests (virtual threads: p99 bound loosened by the implementer; one off-mode p99 check left). | Removed (row 3); latency is printed. |
| R8 | E2E reviewer | Seeds printed but not replayable; smoke wait loop polled only some artifacts. | Fixed (`-Dspringtune.test.seed`, more URLs). |

## 4. Scope

| In | Out (where it goes) |
|---|---|
| `springtune-loadtests` (not published): 12 real-PostgreSQL scenarios, external server or Testcontainers, open-loop JDBC load, server-log and `pg_stat_activity` probes, CI `loadtests` job, `nightly.yml` | Replicas on separate machines; MySQL load runs; Boot 3.5 load runs |
| Product: `BUDGET_EXCEEDS_SERVER` guard (`ServerConnectionLimit`), `ST-REC-HIKARI-004` HOLD for a failing database | Verification requiring the new capacity to be obtained (R2) |
| E2E: apply over WebFlux security, executor apply/revert/ordering, limiter on virtual threads and behind Spring Security | WebFlux on virtual threads, Jetty/Undertow |
| `springtune-benchmarks` (JMH, `-Pbenchmarks`), `benchmarks.yml`, docs/benchmarks.md | Benchmarks on a pinned runner |
| `post-release-smoke.yml` + `scripts/smoke/**`; v0.4.0 notes; v0.3.0 correction; roadmap fixes; version 0.4.0-SNAPSHOT | Gradle corpus re-run (row 9), Kafka/cache rules (row 10) |

## 5. Acceptance criteria and evidence

| ID | Criterion | Verification | Result |
|---|---|---|---|
| AC-4.1 (bar 2) | 3 replicas raising at once stay within the budget; surge excluded → breach detected, included → none; other service, short `maxLifetime`, revert-while-raising all within budget; zero 53300 | `BudgetLoadTest` (7 scenarios) | ✅ CI (Testcontainers) and 6 local passes; service peak 15 ≤ 16; surge-excluded 19 > 16 detected |
| AC-4.2 (bar 3) | Failing database: no INCREASE applied | `DatabaseGuardLoadTest`, `AdvisorTest` | ✅ after the product fix (`ST-REC-HIKARI-004`) |
| AC-4.3 (bar 4) | One revert, no oscillation | `PostgresApplyLoadTest` | ✅ REVERTED once on R2, then `COOLDOWN`, 1 change executed |
| AC-4.4 | Raise confirmed and slow SQL held on real PostgreSQL | `PostgresApplyLoadTest` | ✅ |
| AC-4.5 | `BUDGET_EXCEEDS_SERVER` refuses a budget above the server's limit | `DatabaseGuardLoadTest`, `ApplyGuardTest` | ✅ 409 (100 vs 20) |
| AC-4.6 | Every load test fails under its mutation | mutation runs M1–M6 (implementer), M5–M8 (reviewer) | ✅ M1–M6 killed by the load suite; M7–M8 killed by unit tests (R4) |
| AC-4.7 (bar 5) | Executor ordering both directions end to end; R6 confirm and revert | `ExecutorApplyIntegrationTest` | ✅ 5 rounds, pinned and loaded |
| AC-4.8 (bar 6) | Write operation secured on WebFlux: 401/403/200, anonymous refused | `ApplyReactiveSecurityWebTest` | ✅ |
| AC-4.9 | Limiter on virtual threads caps concurrency; behind Security no 500, no leaked permit | `ServletVirtualThreadsOverloadTest`, `LimiterWithSpringSecurityTest` | ✅ |
| AC-4.10 | JMH gates: tick (4 components) < 1 ms; filter < 10 µs | docs/benchmarks.md | ✅ 376 µs; 0.39 µs (1 thread) / 4.6 µs (8 threads) |
| AC-4.11 | `HikariApplyIntegrationTest` stable, no retries anywhere | 30 consecutive local runs under load | ✅ 30/30, baselines 44.9–49.5 ms |
| AC-4.12 | Smoke fixtures prove a clean install | local runs against a staged repository (Maven, Gradle 8.14.3, 9.8.2) | ✅ locally; on Central after the release |
| AC-4.13 | Nothing regresses: full reactor on Boot 4.1.2 and 3.5.16 | CI | see the PR |

**Apply stays experimental:** bars 2–6 are met, bar 1 (a design partner in production) is not.

## 6. Next

1. Release 0.4.0: Release workflow with version `0.4.0`, Publish on central.sonatype.com, Plugin Portal workflow,
   then `post-release-smoke.yml` (docs/releasing.md).
2. Launch post within a week of the Central release (roadmap step 3); first `gates.md` log entry ~30 days later.
3. 20 consecutive green nightlies before the load suite gates release tags.
