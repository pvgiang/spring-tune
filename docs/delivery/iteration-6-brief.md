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

## 6. Review round

_Filled after the adversarial review._

## 7. Scope

_Filled after merge._

## 8. Acceptance criteria and evidence

_Filled after merge._
