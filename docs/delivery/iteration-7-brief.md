# Iteration 7: Delivery brief (each tag runs its own CLI, honest gate data, a launch the human can post today)

> Follows [iteration 6](iteration-6-brief.md) and the 0.4.2 release (tag `v0.4.2` on `6b3fa10`, 2026-11-15).
> Input: the iteration-6 follow-ups (§9 there), the recurring Action lag (every tag's `action.yml` has defaulted to
> the previous CLI since 0.4.0), the [plan](../business-and-implementation-plan.md) §9 gates and §17 DoD, and the
> [roadmap](../roadmap.md) step 3.

## 1. Goal

> From the next tag on, `uses: pvgiang/spring-tune@vX.Y.Z` with no `version:` runs CLI X.Y.Z, also when pinned by
> commit SHA. The gate numbers we log say which part is our own CI. The launch can be posted today on 0.4.1, from a
> checklist in which every item has a command or a link. The S3 miss is either fixed against a bar registered before
> any new run, or published again as a negative result.

## 2. State at the start (checked 2026-11-15)

- 0.4.2 is released on GitHub (run 43704693076, both jobs green: CLI jar, `SHA256SUMS`, SBOM, maven-repo zip).
  On Maven Central it is uploaded but **not published**:
  `curl -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.2/`
  returned **404**. Therefore the 0.4.1 → 0.4.2 install-line sweep (handoff item 0) is **deferred** (challenge row 1).
- 0.4.1 is on Central; every install line is 0.4.1; `main` is green including `readme-snippets` and `published-action`.
- `main`'s poms said `0.4.2-SNAPSHOT` after 0.4.2 was released (same drift as in iteration 6). This brief's commit
  bumps them to `0.4.3-SNAPSHOT`.
- The `v0.4.2` tag's `action.yml` defaults to CLI 0.4.1.
- Repository **public**, 2 stars (`winkytek` 2026-11-09, `pvgiang` 2026-11-15: both the maintainer's own accounts),
  0 forks, 0 issues, 0 Action users, 0 design partners. Apply experimental (5 of 6 bars; bar 1 = production user).
- `gates-data` holds one snapshot (2026-11-13T08:17:01Z): 122 release downloads (v0.4.1 jar 51, `SHA256SUMS` 51),
  clones 1,055 / 82 unique, views 85 / 2 unique, referrers `github.com` only, `stargazers` **failed with HTTP 403**.
  The same stargazers call works with the session token, so the 403 is the collector's token choice (row 6).
- Baseline: `mvn -B -ntp clean verify -DskipITs` on `main` → BUILD SUCCESS (2026-11-15, this sandbox).

## 3. Human steps, in order (blockers first; none can be done from this environment)

| # | Step | Verify with |
|---|---|---|
| H1 | **Press Publish for 0.4.2** on [central.sonatype.com → Deployments](https://central.sonatype.com/publishing/deployments), then run *Actions → Post-release smoke* with `0.4.2` | `curl -fsI https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.2/springtune-check-cli-0.4.2.pom` → 200; smoke run green |
| H2 | **Sign the [launch checklist](../launch/checklist.md) and post** (r/java, Show HN, dev.to, a JUG). The posts are on 0.4.1 and do not wait for H1 | every agent-checkable item of the checklist passes (`scripts/launch/verify-checklist.sh`); post URLs in the [launch log](../adoption/gates.md#launch-log) |
| H3 | Fill the launch log at 24 h and 7 d | rows in [gates.md](../adoption/gates.md#launch-log) |
| H4 | Run *Actions → Gates collector → Run workflow* once after this PR merges (token-scoping fix, row 6) | the new snapshot on `gates-data` has `"stargazers": {"available": true, …}`: `git fetch origin gates-data && git show origin/gates-data:snapshots.jsonl \| tail -1 \| jq .stargazers.available` |
| H5 | Open one real `feedbackUrl` in a logged-in browser and check the form is pre-filled (anonymous requests get a 302 to the login page) | the title, version, Boot version, recommended value and payload fields are filled |
| H6 | Renew `GATES_TOKEN` before it expires | the latest snapshot has `"traffic": {"available": true, …}` |
| H7 | Run the Gradle Plugin Portal workflow (optional: the Portal already serves the plugin from Central) | `plugins.gradle.org/plugin/io.github.winkytek.springtune` lists the version |
| H8 | Edit the v0.4.0 release body with the Portal correction (optional) | release page |
| H9 | Release 0.4.3 through *Actions → Release → Run workflow* (dispatch, not a pushed tag) when it is due | the new `action-tag` job of *Post-release smoke* with `0.4.3` is green (row 2) |

After H1, the agent's next session runs `scripts/release/sweep-install-version.sh 0.4.1 0.4.2` (row 1); that is not a
human step.

## 4. Challenge log

Planner (PM/architect) proposed, PO/SRE skeptic attacked, the lead decided.

| # | Raised by | Challenge | Decision |
|---|---|---|---|
| 1 | Handoff, both | Sweep install lines to 0.4.2 only if 0.4.2 is on Central. Central returns 404. Planner: defer, but make the sweep one command that refuses until Central answers. | **Deferred.** New `scripts/release/sweep-install-version.sh FROM TO`, which exits 1 with "press Publish" on 404 and "could not check, re-run" on any other non-200 (the iteration-6 R7 semantics), rewrites the install strings the guard scans plus the README Action snippet, the `ci.yml` pins, `EXPECTED_VERSION` and the README capture header, and appends FROM to the stale list. CI runs it in `--skip-central` dry-run mode on a copy and requires the guard and the claims check to pass afterwards. The stale list becomes versions × pattern templates. A seed test shows it flags at least everything the hand-copied list flags. Poms bumped to 0.4.3-SNAPSHOT now, by the lead. A new guard fails when a `docs/releases/vX.Y.Z.md` exists and the pom version is not above X.Y.Z (skeptic C9). |
| 2 | Planner vs PO/SRE | **Action lag.** Planner: derive the version at run time from `github.action_path` (`…/_actions/pvgiang/spring-tune/vX.Y.Z`), then `github.action_ref`, else a static fallback. Skeptic: `action_ref` in composite steps has a history of being empty or naming the wrapper. SHA pins (which OpenSSF recommends) get a 40-hex ref, so they would always fall back. And the proposed PR proof (`uses: ./x/v0.4.2`) tests only the path parsing, never a real tag. Skeptic's alternative: the tagged tree carries its own version. | **Skeptic's design, made automatic.** The Release workflow **stamps** the tagged tree. After build and tests, the release job sets `action.yml`'s `version` default to X.Y.Z in a one-file release commit whose parent is the built commit, pushes it as `refs/tags/vX.Y.Z`, and creates the release on that tag. Every way of pinning the tag (`@vX.Y.Z`, its commit SHA, a fork of the tag) then sees X.Y.Z, and nothing parses refs at run time. `main` keeps the README's pin (the latest version on Central). A pushed tag (path 1 of the workflow) cannot be stamped, so the job fails unless that tag's `action.yml` already defaults to X.Y.Z. Before creating the release, the job checks that the stamped tree defaults to X.Y.Z and differs from the built commit only in that line. **Proof on the PR:** a CI job stamps a copy of the PR's action with `0.4.2` (the jar exists on the GitHub release), runs `uses: ./<copy>` with **no** `version:`, and asserts SARIF tool version 0.4.2 and the demo's findings. `demo` keeps asserting 0.4.1 for the unstamped tree. **Proof after release:** *Post-release smoke* gains `action-tag`: it checks out the tag `vX.Y.Z` into a directory, runs it with no `version:`, and asserts tool version X.Y.Z. That is proven only at the first stamped release, 0.4.3 (H9). An explicit `version:` wins unchanged. Rejected: "empty default = latest release" (a pinned tag would silently pick up new rules). |
| 3 | PO/SRE | **Release downloads and clones are our own CI.** `action-checksum` (every PR and push), `demo` (`uses: ./`, default 0.4.1) and `published-action` (main, weekly) each download the v0.4.1 jar and `SHA256SUMS`, which explains the identical 51/51 counts. 39 CI runs since 2026-11-09. Every job's checkout is a clone. | **In, as measurement honesty.** gates.md states that release downloads and clones are not an adoption signal. The collector records, per release, the number of our own workflow runs since it was published (`own_ci_runs_since`) and the downloads per run, so the residual is visible. The log row (row 9) shows the arithmetic. The jobs keep downloading, because the download-and-verify path is what they prove (reducing them would trade a real CI proof for a cleaner counter). Named teams, issues, form reports and Action users remain the only threshold signals. |
| 4 | Both | Launch. Handoff: refresh `docs/launch` for 0.4.2 and the new README. Skeptic: 0.4.2 has no check-rule changes, the launch is a CI-check story, and tying the post to 0.4.2 adds H1 as a new blocker to the only lever of gate 1. Referrers show only `github.com`: the launch never happened. | **Launch on 0.4.1 now; content refreshed for the new README.** Install lines in the posts stay 0.4.1 (what Central serves). The post links the README's one-path-per-approach layout. Starter features of 0.4.2 (`evaluations`, `/report`) are mentioned only as "0.4.2, on GitHub; on Maven Central after publication", or left out. The checklist becomes executable: each item has a command or a link, and the agent-checkable ones run from `scripts/launch/verify-checklist.sh`. Its first conditional item says that if 0.4.2 is on Central before posting, the sweep (row 1) runs first. Posting stays human (H2). |
| 5 | PO/SRE | `docs/design-partners.md:24-27` still says 0.4.1 is pending on Central and to use 0.4.0. The feedback flow it describes (`/report`, `feedbackUrl`) is 0.4.2-only. | **Fix now:** "0.4.1 from Central today; `/report` and `evaluations` with 0.4.2 once it is on Central (or from the GitHub release's Maven repository zip)". Same for any other stale "pending" statement in `docs/starter.md`. |
| 6 | Both | The `stargazers` 403. `gates-collect.yml` sets `GH_TOKEN: ${{ secrets.GATES_TOKEN \|\| github.token }}`, so the narrowly scoped PAT reads every section. The session token reads the same endpoint fine. `collect.sh` records `auth` as "GATES_TOKEN for traffic; gh credentials for the rest", which is false. | **Fix:** `GH_TOKEN` is `github.token`. The PAT is used only for traffic and for code search. `auth` names the token per section. A local run of `collect.sh` must show `stargazers.available == true`. The real run is H4. |
| 7 | Planner vs PO/SRE | **HIKARI S3 miss.** Planner: candidates P_max (Micrometer's decaying `Timer.max` of `hikaricp.connections.usage`) and P_share (share of checkouts above `slowUsage` from an SLO bucket), evaluated offline on new runs, plus a new S5 where raising is right despite one slow query. Skeptic: the committed polls contain **only means**, so "evaluate against the S3 data" is impossible. Percentiles exist only if the user configures them. `max()` decays over ~2 min, so one slow report query would block every raise. Acquire/hold already fails as a discriminator (S3 ~5×, S2 12–69×). Fitting a rule to 3 seeds of one lock pattern is overfitting. | **Pre-register, measure, change the rule only if a candidate clears the bar.** Step 0, recorded as a finding: the committed 2026-11-09 data cannot evaluate any max/percentile check (only means), and the acquire/hold ratio is rejected on it. Step 1: a pre-registration commit **before any new run**, covering scenarios S1–S4 as registered plus **S3′** (a different lock duty cycle) and **S5** (the S2 pool plus one slow query a minute, where INCREASE is still right), 3 seeded runs each. Polls also record `hikaricp.connections.usage` MAX and an SLO bucket at 500 ms, set by property on the command line (a disclosed change to the fixed inputs). The starter is the locally built main, because 0.4.2 is not on Central; disclosed. Step 2: offline evaluation of both candidates against the bar. **Bar** (per candidate, all of): S3 and S3′ give no INCREASE in any evaluated poll, in 3 of 3 runs each; S2 and S5 give INCREASE as registered, in 3 of 3; S1 has no recommendation; S4 is unchanged; every existing advisor test and the 4,000-window property test pass, with any intended difference listed before the change. Step 3: only if a candidate passes, change the rule (fallback to the mean when the meter is missing) and re-run S3 with the changed starter to say honestly whether S3 now passes. Otherwise publish the negative result and keep the known limitation. Timeboxed (one day). Polls every 30 s only approximate the starter's 10 s samples (a stated limitation). |
| 8 | Planner vs PO/SRE | **Revert of a decrease raises without a limit read.** Planner: never block the automatic revert, but treat a manual revert of a confirmed change that raises the pool like a raise (fresh read, refuse on unknown). Skeptic: never refuse a revert; cap it at the last verified limit and audit it as unverified. | **Split by who reverts.** The **automatic** revert during verification (REGRESSED / INCONCLUSIVE→revert) is the safety path and is **never refused**: it restores a value that held minutes ago and only if the live value is still the applied one. Its audit says the limit was not read (`serverLimit: NOT_READ_ROLLBACK`). A **manual** revert of a confirmed or kept decrease is a new decision to raise. It goes through the same fresh limit read as a raise and is refused with `SERVER_LIMIT_UNKNOWN` / `BUDGET_EXCEEDS_SERVER` like one. The operator can still change configuration, so no one is locked in. The skeptic's cap was rejected because it would write a value nobody recommended. Tests: automatic revert with an unknown limit succeeds; manual revert of a confirmed decrease with an unknown limit returns 409; a mutation that skips the read fails a test. Documented in `docs/starter.md`. Apply stays experimental. |
| 9 | Handoff | A gates.md log row from real collector data. | **In:** a row for 2026-11-13 from the snapshot, with downloads and clones marked as CI-dominated and the arithmetic (row 3). Stars-with-company says "not collected (403, row 6)" rather than 0. |
| 10 | PO/SRE vs planner | R4 (position-aware schema check, WebFlux `/report` test, pseudonyms for not-yet-started pools): skeptic says low value, no leak was proven. | **In, small.** The WebFlux test covers a real code path that is untested today. The position-aware check replaces "any allowed token anywhere" with "the allowed class for this JSON path". A seeded upper-case word in `mode` must fail it. Pseudonym test: a pool that starts after the first report keeps its `hikari#n` across reports. |
| 11 | Planner | Issue-form prefill from a real `feedbackUrl` now that the repo is public. Anonymous `issues/new?…` returns 302. | **Agent part:** `scripts/smoke/check-feedback-prefill.sh` takes a `feedbackUrl` produced by a real `/report` (from a test or a running sample), fetches the public form YAML from `raw.githubusercontent.com`, checks every query key is a field id of that form, and checks the URL length stays under the limit. **Human part:** H5. |
| 12 | PO/SRE vs handoff | Deep dive #4 moves no gate while three are unposted. | **In, cut first.** The 2-week cadence is a §17 commitment, and the posts go out with H2. Rule: **ST-TOMCAT-001** (25 corpus findings, the largest group without a deep dive), every number traced in `claims.md` and recounted by a reviewer. |
| 13 | Both | Docs site. | **Still cut** (no new evidence; iteration-5 row 3 stands). |

### Must not claim

- That 0.4.2 is on Maven Central, or any 0.4.2 install line, before H1 returns 200.
- That `@v0.4.2` (or any existing tag) runs its own CLI by default: it runs 0.4.1. The stamp applies from 0.4.3 and is
  proven only by `action-tag` after H9.
- Downloads, clones or clone uniques as users, installs or teams. "2 unique visitors" as outside interest. The two
  stars are the maintainer's own accounts.
- That the launch has happened, or that any §9 gate is moving (0 issues, 0 Action users, 0 design partners, 0 reports).
- That the S3 miss is fixed, or that a hold check is validated, unless the pre-registered bar of row 7 is met on new
  runs. No PetClinic latency, throughput or "faster" number.
- That `/report` or the pre-filled feedback works on 0.4.1. That the prefill renders, before H5.
- Apply as anything but experimental (5 of 6 bars).

## 5. Implementation split

Each implementer works in its own worktree and first merges this brief's commit.

| Implementer | Owns |
|---|---|
| A, Action and release | rows 1, 2: `action.yml`, `scripts/action/**`, `scripts/release/**` (new), `scripts/check-install-strings.sh`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `.github/workflows/post-release-smoke.yml`, `docs/releasing.md`, `docs/ci-integration.md`, `docs/check.md`, README Action section only |
| B, starter | rows 5, 8, 10, 11: `ChangeController`, `ApplyGuard`, `TuningReport`, report checks and their tests, a new WebFlux report test, `scripts/smoke/check-feedback-prefill.sh`, `docs/starter.md`, `docs/design-partners.md` |
| C, adoption and gates | rows 3, 4, 6, 9, 12: `docs/launch/**`, `scripts/launch/**` (new), `docs/deep-dives/**`, `docs/adoption/gates.md`, `scripts/gates/**`, `.github/workflows/gates-collect.yml`, `scripts/content/check-claims.sh` |
| D, hold-time benchmark | row 7: `scripts/petclinic/**`, `docs/benchmarks/petclinic*` and new run data. Only if the bar is met: `HikariAdvisor`, `HikariSample`, `HikariComponent`, `TimerReader`, `MicrometerSupport` and their tests (B must not touch these) |

The lead owns the poms (0.4.3-SNAPSHOT), `docs/roadmap.md`, `docs/benchmarks.md`, README lines outside the Action
section, and this brief.

## 6. Review round (adversarial reviewers; each defect proven by a failing test or reproduction before the fix)

_Filled after the review._

## 7. Scope

_Filled after the review._

## 8. Acceptance criteria and evidence

| ID | Criterion | Verification | Result |
|---|---|---|---|
| AC-7.1 | No 0.4.2 install line while Central returns 404; the sweep is one command that refuses until Central answers, and whose output passes the guards | `sweep-install-version.sh 0.4.1 0.4.2` on the tree (exit 1, "press Publish"); CI dry run on a copy + `check-install-strings.sh` + `check-claims.sh`; seed test of the templated stale list | |
| AC-7.2 | A stamped tree runs its own CLI without `version:`; an explicit `version:` still wins; the release refuses an unstamped or over-stamped tag | PR job on a copy stamped with 0.4.2 (tool version 0.4.2); `demo` (0.4.1); stamp-script tests; `action-tag` after H9 | |
| AC-7.3 | Gate data is honest: CI share shown, stargazers collected | local `collect.sh` run (`stargazers.available == true`, `own_ci_runs_since` present); gates.md row with arithmetic; validator accepts the new snapshot | |
| AC-7.4 | Every agent-checkable launch-checklist item passes from one script; no stale statement about Central | `scripts/launch/verify-checklist.sh`; `check-claims.sh` | |
| AC-7.5 | HIKARI: bar registered before any run; result reported against it, either way | git order of the pre-registration commit vs the first run's timestamps; `evaluate` reproduces the summary; advisor tests and property test | |
| AC-7.6 | Automatic revert never refused; manual revert of a decrease needs a fresh limit read | `ChangeControllerTest` cases and a mutation | |
| AC-7.7 | Report: position-aware schema check, WebFlux `/report`, stable pseudonyms | new tests, seeded failure | |
| AC-7.8 | Prefill keys match the public form | `check-feedback-prefill.sh` on a real `feedbackUrl` | |
| AC-7.9 | Deep dive #4 with every number traced | `claims.md`; independent recount | |
| AC-7.10 | Nothing regresses | `mvn -B -ntp clean verify -DskipITs` and `-Dspring-boot.version=3.5.16 -Dinvoker.skip -DskipITs` on the merged branch; CI on the PR | |

## 9. Follow-ups for iteration 8

_Filled at the end._
