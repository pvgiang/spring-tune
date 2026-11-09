# Iteration 5: Delivery brief (adoption: a correct first five minutes, a launch, signals from day 1)

> Follows [iteration 4](iteration-4-brief.md) and the 0.4.0 release (first on Maven Central, 2026-11-09).
> Input: [roadmap](../roadmap.md) step 3 and the [plan](../business-and-implementation-plan.md) §9 gate 1 (10+ teams,
> rule requests arriving) and §17.

## 1. Goal

> A Spring Boot team that finds SpringTune through the launch post can copy the README or the Action and get a red
> CI check on risky config **within five minutes, on 0.4.0, with a verified binary**. Every number we publish traces
> to a committed artifact, apply mode is never oversold, and the signals the week-28 gate review needs are collected
> from day 1, without telemetry.

## 2. State at the start (checked against the repository and the GitHub API)

- 0 stars, 0 forks, 0 issues; 0 downloads on every release asset. Repository description, topics and homepage unset.
- `action.yml` defaults to the **0.1.0** CLI (old groupId and rules), and downloads it without a checksum. README,
  `docs/ci-integration.md` and the case study show `@v0.1.0` / 0.3.0 coordinates; the README says "Maven Central from
  0.3.0" (false).
- `docs/releases/v0.4.0.md` says the Gradle plugin is on the Plugin Portal. It is not yet (the Portal proxies Maven
  Central, so `plugins { id(...) }` resolves anyway; see the 0.4.0 smoke run).
- No SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md, Dependabot.

## 3. Challenge log

PM/devrel proposed, PO/SRE skeptic attacked.

| # | Raised by | Challenge | Decision |
|---|---|---|---|
| 1 | Both | The first copy-paste runs the 0.1.0 CLI; coordinates and claims are stale. | **Must, first.** 0.4.0 everywhere outside `docs/releases/` and `docs/delivery/`; Action default 0.4.0; a CI guard fails on stale versions; a CI job **executes** the README snippets in a fresh project (Maven, Gradle via `mavenCentral()`, the Action). |
| 2 | PO/SRE | The Action runs an unverified jar in someone's CI: a worse trust problem than any badge. | **Must.** The Action verifies the jar's SHA-256 against the release's `SHA256SUMS` (already a release asset) and fails on mismatch; a tampered-jar test proves it. |
| 3 | PM vs PO/SRE | Docs site (MkDocs on Pages): PM rank 3; skeptic: no analytics, doubles the surface where claims drift, GitHub-rendered docs are where traffic is measured. | **Cut for now.** Revisit after 3+ deep dives for SEO. |
| 4 | Both | Demo: "see it fail on my PR in 2 minutes" is the conversion step. | `examples/broken-config-ci/`: standalone project on Central 0.4.0 + copy-paste workflow. A CI job (also weekly) runs the local Action on it and asserts **expected failure**: exit 1, the expected rule IDs in SARIF with line locations; any other failure is a broken demo. Publishing it as a separate public repository is a human step. |
| 5 | PO/SRE | Every published number must trace to a committed artifact; the case study ran on a pre-0.4.0 version. | Re-run the corpus with 0.4.0 if the sandbox can clone the repositories; otherwise every number cites its source and version explicitly. The 0.5% FP rate is stated as "1/199, reviewed by the author". |
| 6 | PO/SRE | Apply mode oversold would burn the pitch. | Launch headline is the **CI check + read-only advice**. Apply appears only as "experimental, opt-in; 5 of 6 bars met, no production user". Banned words in content: production-ready, self-tuning, safe auto-apply (a CI grep enforces it on `docs/launch/` and deep dives). |
| 7 | PO/SRE | R2 (a CONFIRMED verdict while the pool never obtained the new capacity) makes the audit trail lie. | **Fix now, small:** the verdict records `capacityObtained` (total connections reached the target during verification, yes/no), shown in the audit, the response and a metric tag; it does not revert (iteration-4 decision stands). Regression test on real PostgreSQL. |
| 8 | PO/SRE | R3: the server limit is read once; an operator can lower `max_connections` later. | **Fix now, small:** re-read the server limit once per apply. |
| 9 | PM vs PO/SRE | CLA: plan says CLA; skeptic: it deters the first contributor. | **DCO** (sign-off) in CONTRIBUTING.md; CLA deferred to the paid controller, which is separate code. |
| 10 | PM vs PO/SRE | Scorecard badge; CodeQL. | Scorecard runs as a workflow, results logged, **no badge unless score ≥ 7**. CodeQL cut (no user input surface; revisit if Scorecard flags it). Dependabot on, grouped, monthly, no auto-merge. |
| 11 | PM | CycloneDX SBOM per published module, attached to the release and deployed with Central artifacts. | In, if it stays inside the `release` profile and passes the JReleaser dry run. |
| 12 | PO/SRE | GitHub traffic (views, clones, referrers, paths) is kept 14 days only: the one signal lost forever if not collected; also the only way to see which launch channel worked. | **Collector:** weekly + manual workflow, appends JSON to an orphan `gates-data` branch (not `main`). Traffic needs a fine-grained PAT (Administration: read) as a secret, a human step; without it the workflow collects what `GITHUB_TOKEN` can and says what is missing. Plus release asset downloads, stargazers with timestamps, issues by label, Action users via code search. |
| 13 | Both | Gates log needs a release-day baseline and a launch log. | 2026-11-09 baseline row; a launch-log table (URL, 24 h and 7 d score/comments, referrer delta). Central download stats are owner-only: a human copies them monthly. |
| 14 | PO/SRE | Launch content. | Drafts only (posting is human): launch post + r/java / Show HN variants, and two deep dives (ST-HIKARI-001, ST-HTTP-001), each linking the rule page and the demo; a pre-post checklist (smoke green, snippets executed, claims traced). |
| 15 | PM | Portal correction for 0.4.0. | Correction note in `docs/releases/v0.4.0.md` (same pattern as 0.3.0); editing the GitHub release body is a human step. |

## 4. Human steps (cannot be done from this environment)

1. Run the Plugin Portal workflow (needs `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET`); the Portal reviews the first version.
2. Set the repository description, topics and homepage.
3. Edit the v0.4.0 GitHub release body with the Portal correction.
4. Turn on private vulnerability reporting.
5. Create the fine-grained PAT for the traffic collector and store it as a secret.
6. Optionally publish `examples/broken-config-ci` as its own public repository.
7. Post the launch (r/java, Show HN, dev.to, a JUG) and fill in the launch log at 24 h and 7 d.
8. Copy Maven Central download stats into the gates log monthly.

## 5. Scope, acceptance criteria and evidence

(Filled in at delivery.)
