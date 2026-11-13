# Iteration 6: Delivery brief (a first staging run that a stranger can finish and report)

> Follows [iteration 5](iteration-5-brief.md) and the 0.4.1 release (tag `v0.4.1`, 2026-11-09).
> Input: [roadmap](../roadmap.md) step 3 (design partners, benchmark write-up, deep-dive cadence), the
> [plan](../business-and-implementation-plan.md) §9 gate 2 (≥ 30% accepted) and §16 DoD, and the accepted
> follow-ups R3 and R10 of the iteration-5 review round.

## 1. Goal

> A team that adds the starter in staging knows within ten minutes what it will see and when, can tell "healthy"
> from "still collecting" from "metrics missing", and can report each recommendation through the feedback form with
> a payload it can paste **without leaking a hostname, a pool name or a principal**. Every install line points at
> 0.4.1; every measurement keeps the version that produced it. The PetClinic run says what the starter recommends,
> and what it does not, with no latency headline.

## 2. State at the start (checked 2026-11-09)

- 0.4.0 on Maven Central. 0.4.1 tagged and released on GitHub (CLI jar, `SHA256SUMS`, SBOM); the Central
  deployment is uploaded but **not published**: `repo1.maven.org/.../springtune-spring-boot-starter/0.4.1/` returns
  **404**.
- The `v0.4.1` tag's own `action.yml` defaults to CLI **0.4.0** (verified in [v0.4.1 notes](../releases/v0.4.1.md)),
  so every doc passes `version:` explicitly.
- `main`'s poms say `0.4.0-SNAPSHOT` although 0.4.0 and 0.4.1 are released (the release workflow sets the version
  only in the release job).
- Repository **private**; 0 stars, 0 issues, 0 users; apply experimental (5 of 6 bars, bar 1 = production user).
- The feedback form misses `ST-REC-HIKARI-004`, has no recommended/applied value, no team, no mode, and its version
  placeholder says 0.3.0.

## 3. Human steps, in order (blockers first; none can be done from this environment)

| # | Step | Verify with |
|---|---|---|
| H1 | **Make the repository public** | `gh api repos/pvgiang/spring-tune --jq .visibility` → `public` |
| H2 | **Press Publish for 0.4.1** on central.sonatype.com | `curl -fsI https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1.pom` → 200; then re-run the `readme-snippets` jobs |
| H3 | Run the Plugin Portal workflow (`GRADLE_PUBLISH_KEY`/`SECRET`) | `plugins.gradle.org/plugin/io.github.winkytek.springtune` lists 0.4.1 |
| H4 | Create `GATES_TOKEN` (fine-grained PAT, Administration: read) and run *Gates collector* once by hand | the run summary says `traffic: collected` |
| H5 | Repository description, topics, homepage; private vulnerability reporting | repository settings |
| H6 | Edit the v0.4.0 release body with the Portal correction | release page |
| H7 | Sign the [launch checklist](../launch/checklist.md), post, fill the launch log at 24 h and 7 d | [gates.md](../adoption/gates.md) |
| H8 | Tag the next release (its `action.yml` defaults to 0.4.1, so `@v0.4.2` works without `version:`) | `post-release-smoke` |

## 4. Challenge log

Planner (PM/architect) proposed, PO/SRE skeptic attacked, the lead decided.

| # | Raised by | Challenge | Decision |
|---|---|---|---|
| 1 | Both | Sweep install lines to 0.4.1; but 0.4.1 is not on Central, and `readme-snippets` resolves the README against Central after every merge to main. Skeptic: move only the Action pin now, Maven coordinates after a Central-200 check. | **Sweep everything now** (the handoff asks for it; a README that installs an older version than the latest release is drift too). The `readme-snippets` job gets a first step that checks Central for the README's version and fails with *"0.4.1 is not on Maven Central yet: press Publish (H2)"*, so the red after merge is self-explaining and turns green on re-run after H2. Never a skip, never a pass. The stale list gains the 0.4.0 install strings; measurement claims keep 0.4.0. |
| 2 | PO/SRE | The `published-action` job skips while the repository is private because the v0.4.0 Action used anonymous curl. | `@v0.4.1` downloads through the API with the workflow token (R5), so the job runs while private. Proven only by CI after merge to main (the job runs on main). |
| 3 | Planner vs PO/SRE | Shareable export: planner a `GET /actuator/tuning/report` selector; skeptic a documented `jq` filter (a new operation is new surface), and "redacting hostnames" is the wrong model: pgjdbc messages carry `host:port` and role names, ids carry pool and bean names, the audit carries `who`/`why`. | **Selector, built from an allowlist.** It is a read operation on the already exposed `tuning` endpoint and returns strictly less than `GET /actuator/tuning`; a `jq` filter cannot pseudonymize consistently and needs `jq` on a locked-down host. Nothing is scrubbed afterwards: only enums, numbers, instants, rule IDs matching `ST-REC-[A-Z]+-\d{3}`, setting names from a fixed set, and ordinal pseudonyms (`hikari#1`) are copied. Free text (`reason`, `who`, `why`, exception messages, evidence that is not a number) is never copied. A schema test walks every string leaf and fails on anything outside that grammar; a trap test proves none of a hostile fixture's strings survive. |
| 4 | PO/SRE | **An empty `recommendations[]` is ambiguous.** HIKARI-003 needs 24 h, HIKARI-001 80% of 5-minute samples; a healthy service shows `[]` for a day and a first user concludes it is broken. | **In, highest value for gate 2.** Each rule reports an evaluation status per component: `COLLECTING` (with samples or time seen vs needed), `NO_SIGNAL` (a required meter is missing, named), `QUIET` (evaluated, nothing to recommend), `RECOMMENDING`. In the snapshot and in the report. |
| 5 | PO/SRE | The feedback form cannot measure gate 2: no HIKARI-004, no recommended/applied value (so `rec-edited` is unverifiable), no team, no mode, no Boot version, placeholder 0.3.0. | **Fix the form**; payload field asks for `/actuator/tuning/report` output; bracketed outcome tokens unchanged so the gates queries keep matching. |
| 6 | Planner vs PO/SRE | Design-partner kit: planner two pages + a new issue form; skeptic: zero users, so a "partner" kit has no reader; one "first staging run" page is what a stranger uses. | **One page**, `docs/design-partners.md`: what we ask (2 weeks advise in staging, one issue per recommendation), what you will see and when (statuses from row 4), how to report (row 3, row 5), how we count gate 2 (link to gates.md, not duplicated), how to leave. Linked from the README and `docs/starter.md`. No new issue form. |
| 7 | PO/SRE | PetClinic queries take milliseconds; on 4 vCPU shared with the load generator the bottleneck is CPU, and HIKARI-001 may recommend a raise that would not help. Forcing windows guarantees the result. | **Pre-registered, timeboxed (one day).** Expectations committed before the first run; PetClinic pinned to the corpus SHA `500158f7`; PostgreSQL 16 local; seeded open-loop load. Scenarios: S1 defaults at moderate rate; S2 a constrained pool; S3 a database-side slowdown (expect *don't raise*); a decrease scenario only with its compressed window stated in the title. Report **rule outcomes and the signals behind them**, never latency or throughput headlines; "no recommendation" and "fired where raising would not help" are published as findings. Limitations name the CPU contention. |
| 8 | PO/SRE | Skipping the limit read for decreases can reopen R1: the read decision is made before the recommendation `apply()` evaluates is known; `ApplyGuard` refuses only on `NOT_READ`. | **In, fail closed.** Direction decided in the same lock hold from the same `apply(now)` result; a skipped read carries a distinct `SKIPPED_FOR_DECREASE` status that `ApplyGuard` refuses for any raise (`SERVER_LIMIT_UNKNOWN`). Test: the recommendation flips from decrease to raise between holds and the raise is refused. |
| 9 | PO/SRE | A hung limit read also **pins one pool connection** on the reader thread, not just refused raises. | **In.** `setNetworkTimeout(Runnable::run, wait)` on the borrowed connection (Hikari restores it on close; a timed-out socket is evicted); `SQLFeatureNotSupportedException` keeps today's behaviour. Proven on real PostgreSQL with a TCP relay that stops forwarding: the read ends within the bound and the pool recovers. |
| 10 | Both | `GATES_TOKEN` and `contents: write` share one job. | **Split:** `collect` (read-only, holds the PAT, uploads the snapshot) and `store` (write, no PAT, validates and pushes). Proven by reading the YAML and actionlint; a real run is H4. |
| 11 | PO/SRE vs handoff | Deep dive #3: nobody can read it until the repository is public; two drafts are unposted. | **In, cut first.** The 2-week cadence is a §17 commitment and posts are staged for after H1. ST-TX-001 (70 corpus findings, the largest group), traced in `claims.md`. |
| 12 | PO/SRE | Human-step debt is the real blocker, scattered across four docs. | One ordered table with a verification command each (§3). |
| 13 | Both | Docs site. | **Still cut** (no new evidence; iteration-5 row 3 stands). |

### Must not claim

- That 0.4.1 is on Maven Central before H2 returns 200.
- That the Action verifies the jar for `@v0.4.0`.
- Any PetClinic latency, throughput or "faster" number, that the benchmark represents production, or that a
  recommendation is correct (only that it fired, with its signals).
- "Redacted" or "safe to paste" for anything but the allowlisted report.
- Any user, partner or team count above zero; that apply is anything but experimental (5 of 6 bars).

## 5. Implementation split

| Implementer | Owns |
|---|---|
| A, release and docs | rows 1, 2, 5, 11: `action.yml`, README, `docs/check.md`, `docs/ci-integration.md`, `examples/**`, case-study install lines, `docs/launch/**`, `docs/deep-dives/**`, `.github/workflows/ci.yml`, `scripts/check-install-strings.sh`, `scripts/content/check-claims.sh`, `scripts/smoke/**`, `.github/ISSUE_TEMPLATE/**` |
| B, starter | rows 3, 4, 6: `springtune-spring-boot-autoconfigure` main and tests except the files of C, `docs/starter.md`, `docs/design-partners.md` |
| C, apply follow-ups | rows 8, 9: `HikariComponent`, `ChangeController`, `ApplyGuard`, `ServerConnectionLimit` and their tests, `springtune-loadtests/**`, `docs/loadtests.md` |
| D, benchmark and collector | rows 7, 10: `scripts/petclinic/**`, `docs/benchmarks/petclinic*`, `.github/workflows/gates-collect.yml`, `docs/adoption/gates.md` |

The lead owns the poms (bump to 0.4.2-SNAPSHOT), `docs/releases/v0.4.2.md`, `docs/roadmap.md` and this brief.

## 6. Review round (adversarial reviewers; each defect proven by a failing test or reproduction before the fix)

| # | Raised by | Finding | Decision |
|---|---|---|---|
| R1 | Report reviewer | **Leak:** any logged-in caller's refused `POST /actuator/tuning` is audited with its `ruleId` as sent; the report copied anything matching `ST-REC-[A-Z]+-\d{3}`, so `ST-REC-ORDERSDB-001` (a pool name in capitals) reached `verdicts[]`, past both the trap test (lower-case strings) and the schema check. | **Fixed:** only SpringTune's five rule IDs are copied, everywhere in the report; the test (`ruleIdFromARequestBodyIsNotCopiedUnlessItIsAKnownRule`) failed on the old code. |
| R2 | Report reviewer | The property test compared `evaluate` with itself (`advise` delegates to it): a `>=`→`>` mutation in the saturation check passed. | **Fixed:** compares against a copy of the 0.4.1 `advise` logic; the mutation now fails. |
| R3 | Report reviewer | `docs/starter.md` presented a hand-built hostile snapshot as real output; it contradicted the page (e.g. NO_SIGNAL on an executor). | **Fixed:** labelled as hand-built test data, with what a real service never shows. |
| R4 | Report reviewer | Schema check ignores position (an upper-case word would pass in `mode`); pseudonym numbering of not-yet-started pools; WebFlux `/report` untested. | Not proven as leaks (every such field is an enum today). Follow-up. |
| R5 | Apply reviewer | **The limit read's network timeout could stay on a pooled connection:** HikariCP restores it only if its own start-up probe (get, then set) succeeded; a driver that can set but not get the timeout left `connectionTimeout` on the connection for every later query. | **Fixed:** the bound is set only when `getNetworkTimeout()` works; new driver mode `GET_NOT_SUPPORTED`, test failed on the old code. Load suite 13/13 on PostgreSQL 16 after the fix. |
| R6 | Apply reviewer | No path found that approves a Hikari raise without a fresh limit read (mutations: always-read, never-read, no early refusal, off-by-one in `raisesHikariPool`: all caught). A revert of a decrease raises without a read (since 0.4.0). | Accepted, documented in the 0.4.2 draft notes; whether a rollback may be blocked is a design question for the next iteration. |
| R7 | Docs/CI reviewer | The Central check reported a 429/5xx as "not published, press Publish" (reproduced live: Central answered 429 for 0.4.0). | **Fixed:** 404 → "press Publish"; any other non-200 → "could not check, re-run"; never skips or passes. |
| R8 | Docs/CI reviewer | Install guard missed Gradle starter coordinates, the Groovy plugin DSL, `maven-plugin:0.4.0:check` and `releases/download/v0.4.0/`. | **Fixed:** six patterns; a seeded copy now fails, the tree passes. |
| R9 | Docs/CI reviewer | The demo README sent users to a 404 (0.4.1 from Central) with no fallback; stray `*` in README and launch post. | **Fixed.** |
| R10 | Docs/CI reviewer | Re-counted every deep-dive #3 number from the raw 0.4.0 JSON with an independent script, and the rule-behaviour claims against `TransactionTimeoutRule`. | All match. |
| R11 | Benchmark reviewer | **The poll window was narrowed after the runs and it changes a verdict:** the pre-registration evaluates polls "from minute 6 on"; 5 of 7 after-load S2 polls carry no recommendation. Counted literally, **S2 is a miss**. Calibration polls ran past the end of their step; the data directory differed from the fixed inputs. | **Disclosed** under "Deviations from the pre-registration" (the pre-registration text itself is unchanged since `3da677f`, verified by diff). Headline in README and `benchmarks.md` changed by the lead to "2 of 4 as registered, S2 only with a bound added afterwards, 1 miss". |
| R12 | Benchmark reviewer | S1–S4 evidence ranges taken from all polls instead of the evaluated ones (e.g. S3 acquire 762–1,111 ms vs 762–778 ms); `evaluate.sh` in the reproduce block read our data, not the reader's. | **Fixed** in `petclinic.md`; `evaluate.sh` reproduces `summary.tsv` byte for byte. |
| R13 | Implementer D | **S3 miss:** a lock on one table produced `ST-REC-HIKARI-001` INCREASE 10→13 instead of "don't raise": mean hold 141–146 ms (threshold 500 ms) because each cycle's blocked connections are averaged with many short checkouts. | **Published as a finding** and as a known limitation in the 0.4.2 draft. A percentile-based hold check is a candidate fix for the next iteration (not implemented, not validated). |

## 7. Scope

| In | Out (where it goes) |
|---|---|
| Install lines to 0.4.1 (`@v0.4.1` + `version: "0.4.1"`), Action default 0.4.1, stale guard for 0.4.0 install strings, Central check before the README snippets, `published-action` on `@v0.4.1` without the private skip; version 0.4.2-SNAPSHOT | Central publication of 0.4.1 (H2) |
| Per-rule `evaluations` (COLLECTING / NO_SIGNAL / QUIET / RECOMMENDING); allowlisted `GET /actuator/tuning/report` with `feedbackUrl` | A `jq` filter (row 3); WebFlux test of `/report` (R4) |
| Feedback form: all five IDs, recommended/applied value, team, mode, Boot version, reverted; `docs/design-partners.md` | A design-partner issue form (row 6) |
| Bounded limit read (`setNetworkTimeout`, only when restorable), no read for decreases with fail-closed `SKIPPED_FOR_DECREASE` | Limit read on revert (R6); HIKARI-002 percentile hold (R13) |
| PetClinic benchmark, pre-registered, 12 runs, deviations disclosed | Latency numbers; a second (design-partner) service |
| Gates collector split (read-only collect with the PAT, write-only store); design-partner row in gates.md | A real collector run (H4) |
| Deep dive #3 (ST-TX-001), claims traced | Docs site (row 13) |

## 8. Acceptance criteria and evidence

| ID | Criterion | Verification | Result |
|---|---|---|---|
| AC-6.1 | Every user-facing install line is 0.4.1; measurements keep 0.4.0; no claim that 0.4.1 is on Central | `check-install-strings.sh` (43 files), `check-claims.sh`; reviewer R7–R9 | ✅ locally. `readme-snippets` is **red until H2** (Central returns 404 for 0.4.1 on 2026-11-09), by design, with the "press Publish" message |
| AC-6.2 | `@v0.4.1` runs on the demo in a private repository | `published-action` job (main only) | ⏳ only CI on main can prove it |
| AC-6.3 | An empty `recommendations[]` is never ambiguous | `RuleEvaluationTest` (incl. 4,000-window property test against 0.4.1 logic) | ✅ |
| AC-6.4 | The report copies no host, pool/bean name, principal, operator text or exception text; any free text fails CI | `TuningReportAdviseWebTest`, `TuningReportApplyWebTest` (trap + grammar, over HTTP, with Security), mutations (reason copied; free-text field; R1 request text) | ✅ on Boot 4.1.2 and 3.5.16 |
| AC-6.5 | The form can measure gate 2 | form fields; `feedbackUrl` prefill ids exist in the form; outcome tokens unchanged | ✅ (GitHub's actual prefill is checked only once public) |
| AC-6.6 | A hung limit read ends within its bound and releases its connection; decreases never wait; no raise without a fresh read | `ServerLimitHangLoadTest` on PostgreSQL 16 (refused after ~3.2 s, connection released ~3.0 s, pool back to 4, next raise 200); `ChangeControllerTest`, `ApplyGuardTest`, `ServerLimitNetworkTimeoutTest`; mutations M9–M11 and R6 | ✅ load suite **13/13** on PostgreSQL 16 (implementer and reviewer, separately) |
| AC-6.7 | PAT never in a job with `contents: write`; snapshot validated before commit | YAML reading, actionlint (clean), validator run on a real and 8 broken snapshots | ✅ locally; real run is H4 |
| AC-6.8 | PetClinic: pre-registered before any run, reproducible, no latency claim, misses published | git order (`3da677f` 11:23 UTC, first run 14:13 UTC), pinned SHA, seeds, `evaluate.sh` byte-identical, reviewer R11–R12 | ✅ with disclosed deviations: S1 and S4 match, S2 match only with a post-hoc bound, S3 miss |
| AC-6.9 | Deep dive #3 with every number traced | `claims.md`; independent recount (R10) | ✅ |
| AC-6.10 | Nothing regresses | `mvn -B -ntp clean verify -DskipITs` and `-Dspring-boot.version=3.5.16 -Dinvoker.skip -DskipITs` on the merged branch | ✅ both BUILD SUCCESS, 13 modules, autoconfigure 150 tests each; CI matrix (JDK 17/21/25) see the PR |

## 9. Follow-ups for iteration 7

1. HIKARI-002 misses a slowdown that hits a minority of connections (R13): evaluate a percentile or max hold check
   against the S3 data before changing the rule.
2. Revert of a decrease without a limit read (R6): decide whether rollback may be refused.
3. Position-aware report schema check; WebFlux `/report` test; pseudonyms for not-yet-started pools (R4).
4. After H1/H2: re-run `readme-snippets`, check the issue-form prefill from a real `feedbackUrl`, post deep dives.
