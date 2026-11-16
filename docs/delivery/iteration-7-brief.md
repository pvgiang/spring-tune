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

**Addendum to row 7 (lead, 2026-11-15 ~23:10 UTC, after S1 ×3 and S2 run 1, before any S3, S3′, S4 or S5 run).**
Implementer D found, after its pre-registration, that the two SLO bucket gauges are a decaying ~60 s window read as
two separate snapshots. In 13 of 64 polls `le:0.5` reads higher than `le:1h`, which one snapshot cannot do. So the
P_share input measured here is not the estimator an implementation would compute from one `takeSnapshot()`.
Decision, made before the remaining data exists:
- **P_share is reported, but as "not validated"** whatever its table says.
- **The rule may change in this iteration only if P_max meets the bar.** P_max reads a single gauge.
- Every other part of row 7 stands as registered.

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

| # | Raised by | Finding | Decision |
|---|---|---|---|
| R1 | Release reviewer | **A re-run would reuse a tampered tag.** `release-commit.sh` accepted an existing tag whose commit changed at most 4 lines of `action.yml` and defaulted to X.Y.Z. A tag that also swapped the `setup-java` pin was accepted (rc 0). | **Fixed:** the tag's `action.yml` must be byte for byte what `stamp-action-version.sh` makes of the built commit's. The new test failed on the old code. |
| R2 | Release reviewer | **`action-tag` could only fail for 0.4.2**, so H1's "smoke green" was impossible. The `v0.4.2` tag defaults to 0.4.1. Reproduced: the SARIF written by 0.4.1 vs `--tool-version 0.4.2` gives rc 1. | **Fixed:** the job reads the tag's default. It runs and asserts when the default equals the version, prints a notice for unstamped tags below 0.4.3, and fails for an unstamped tag from 0.4.3 on. |
| R3 | Release reviewer | **The sweep produced false docs with every guard green.** A dry run put 0.4.1's SHA-256 next to `springtune-check-cli-0.4.2-all.jar`, so the copied `sha256sum -c` snippet would fail. It also rewrote "the `v0.4.1` tag's Action downloads the jar without checking it" (false) and dated evidence lines. | **Fixed:** a `sweep: keep` marker that the sweep and the guard both honour. The "tag defaults to CLI" pattern now requires the word *default*. A rewritten line that carries a 64-hex hash stops a real sweep before anything is written. The lead marked the history lines in `docs/launch/` and moved the verbatim checklist run log to `docs/adoption/launch-checklist-runs/`. `check-claims.sh` honours the marker. A dry sweep 0.4.1→0.4.2 on a copy now rewrites only live lines and passes both guards. A real sweep stops at the post's checksum line until that line is updated by hand. |
| R4 | Release reviewer | Templates starting with the version had no left boundary: `<version>10.4.0</version>` was flagged as stale 0.4.0, and the sweep rewrote `2.0.4.1` to `2.0.4.2` (seeded copy). | **Fixed:** no digit or dot may precede the version. No such line exists in the real tree. |
| R5 | Release reviewer | `release.yml` accepts `1.2.3.4` but `release-commit.sh` rejects it, so the run fails only after the full build. | Accepted (it fails late, but can release nothing wrong). Follow-up. |
| R6 | Release reviewer | The templated stale list vs the old hand list, re-derived independently: the old list flagged 56 lines, the new one 57. The only line flagged by the old list alone is the capture record `docs/readme-runs/README.md:7`, which is now deliberately excluded. A no-op stamp fails 9 stamp tests and 14 release-commit tests. The v0.4.2 release jar passes `assert-sarif --tool-version 0.4.2 --exact` on the demo. | No defect. `gh release create --verify-tag` exists (gh 2.89.0). A tag pushed by the job token starts no workflow: this is documented GitHub behaviour, not reproducible here. |
| R7 | Starter reviewer | The report's schema check still let any `ST-REC-<WORD>-nnn` through in the feedback URL's `title`, so a pool name in capitals passed. Not a leak: the report copies only the five rules. | **Fixed:** the title takes only SpringTune's five rule IDs. The test failed on the old check. |
| R8 | Starter reviewer | `check-feedback-prefill.sh` passed URLs with a `#` fragment (a browser drops the rest of the payload), and `--form` without a path exited 1 instead of 2. | **Fixed;** a real `feedbackUrl` still passes. |
| R9 | Starter reviewer | Revert paths attacked: revert of the change in flight, an apply between the two lock holds, revert after SUPERSEDED, knob changed by others, executor knob, two concurrent reverts. The pre-refactor `ApplyGuardTest` was run unchanged on the new code (17/17). A mutation that skips the read fails 3 tests. | **No path found** that raises a Hikari pool through a revert without a fresh read. A manual revert that raises without a budget is refused (`NO_BUDGET`), and above the per-instance cap (`ABOVE_BUDGET_CAP`), exactly like an apply that raises. Documented in `docs/starter.md`. |
| R10 | Starter reviewer | Pseudonyms in discovery order: the order is fixed for the life of the process. Pools that share an id get a number but nothing is attributed to them. The WebFlux test runs a real Reactor Netty server with reactive Security and the real endpoint. | No defect. (Implementer B had already proven and fixed the underlying pseudonym bug: an unnamed pool's id changes when it starts, which swapped `hikari#n` numbers. The test failed on the old code.) |
| R11 | Gates reviewer | **The snapshot validator accepted arbitrary JSON in front of a valid snapshot** (`jq -e` judges only the last value). `{"schema":99}` followed by a real snapshot gave "snapshot ok", so the store job would have appended junk to `snapshots.jsonl`. | **Fixed:** the whole file is read (`jq -s`) and must be exactly one value. 19 malformed inputs are refused, and the real snapshot passes. |
| R12 | Gates reviewer | **Deep dive #4's "bounded configuration" arithmetic was wrong.** With a 2 s `connectionTimeout` the text said 20 of 100 Tomcat threads stay free. HikariCP serves waiters in arrival order (`ConcurrentBag`'s fair `SynchronousQueue`, checked in the 6.3.3 bytecode), so a simulation gives all 100 threads taken. | **Fixed:** the example uses 1,000 ms (40 of 100 free, simulated), and the text says 2 s would leave none. The claims row now cites the simulation. |
| R13 | Gates reviewer | gates.md's "49 pairs" counted one `action-checksum` job whose download died after 2.5 s (run 42638709344). | **Fixed:** 48–49 pairs against the counter's 51; the residual is 2–3. The clones vs jobs figures (817 jobs, residual 238) reproduce exactly. |
| R14 | Gates reviewer | Every deep-dive #4 number recounted with independent scripts: 25 findings, 7 repos, all INFO, 16 in two tutorial collections, 25 of 25 also ST-HIKARI-001, 38 modules split 25 / 5 / 4 / 4, pool sizes 20, 20, 20, 30, 25 HikariCP modules without Tomcat. The `GATES_TOKEN` scoping, the history `jq` queries over old and new snapshots, and `verify-checklist.sh` mutations (each turns an item FAIL or NO-CHECK and makes the run exit 1) were checked. | All match. Not proven: a bogus `GH_TOKEN` test (the sandbox proxy supplies credentials). Low, open: L13 has no age check on the snapshot (follow-up). |
| R15 | Implementer D | **After pre-registering, D found the SLO bucket gauges are a decaying window read as two snapshots:** in 13 of 64 polls `le:0.5` > `le:1h`. P_share's measured input is therefore not what an implementation would compute. | **Lead decision recorded before any S3/S3′/S4/S5 run** (§4 addendum, commit `878cc32`): P_share is reported as not validated, and the rule may change in this iteration only through P_max. |
| R16 | Benchmark reviewer | Five figures in `petclinic-hold.md` did not match the raw polls: window size 8–11 (the windows carrying an INCREASE have 10–11 polls), S3 mean acquire 761–817 ms (data: 761–779), S3′ waiting 81–97 % (81–90), S5 acquire 25–50 ms (25–49), S3 acquire/hold 5.2–5.7 (5.24–5.51). Reproduced with an independent extractor over the raw files. | **Fixed** (`6de63bd`). |
| R17 | Benchmark reviewer | The new-runs acquire/hold table showed only S2 run 2 (ratio 5–6, "same as the lock"). The other runs: S2 run 1 69–74, run 3 19–20, S5 25–49. | **Fixed** (`13c46a7`): every run shown. The ratio overlaps the lock in 1 of 3 S2 runs and varies more than tenfold between seeds, which strengthens the step-0 rejection. |
| R18 | Benchmark reviewer | Undisclosed timing: `evaluate-hold.py` and the row-7 addendum were committed after S1 ×3 and S2 run 1 had been seen (both before S2 run 2). "Each run records a different commit" was false: S1 ×3 and S2 run 1 all record `6f1d188`. S3′ run 2 passes for every rule only because no INCREASE fired in it. | **Fixed** (`bb2b035`): two deviation bullets, a footnote on the bar table, corrected wording. The pre-registration section stays byte-identical to `6f1d188`. |
| R19–R24 | Benchmark reviewer | `evaluate-hold.py` matches every registered definition. The only post-run change is the consistency table, which sits outside the bar. Pre-registration at 13:43:52 predates the first run (driver at 13:45:49). An independent recount gives 288 polls with 0 failed, the same per-scenario outcomes, and 23 inconsistent bucket pairs. The jar SHA is identical in all 18 runs. Deviations are disclosed, there are no latency or throughput claims, and no main code was changed. Both guards pass. | No defect. Not verifiable: the 70-second plumbing run, whose output was discarded (disclosed). The lead re-ran the evaluation on the merged branch and got the same bar table. |

## 7. Scope

| In | Out (where it goes) |
|---|---|
| Poms 0.4.3-SNAPSHOT; a one-command install sweep that refuses until Central answers 200, keeps marked history, and stops on a stale checksum; templated stale list; pom-vs-release-notes guard | The sweep itself (0.4.2 not on Central: H1, then the next session) |
| The Release workflow stamps the tag's `action.yml` default (dispatch path); a pushed tag must be pre-stamped; a PR job proves a stamped copy runs 0.4.2 with no `version:`; `action-tag` in *Post-release smoke* | Proof on a real tag (first stamped release 0.4.3, H9) |
| Automatic rollback never refused or blocked on a limit read; a manual revert that raises a Hikari pool goes through the raise checks | Capping a revert at the last verified limit (row 8, rejected) |
| Position-aware report schema check; WebFlux `/report` test; stable pseudonyms (bug fixed); `check-feedback-prefill.sh` | The rendered prefill (H5) |
| Collector on `github.token`, PAT only for traffic and code search; `own_ci_runs_since`; stricter snapshot validator; 2026-11-13 log row with the CI arithmetic | A real collector run (H4); reducing CI's own downloads (row 3) |
| Launch content on 0.4.1 for the new README; executable checklist (`verify-checklist.sh`, 13 PASS); deep dive #4 (ST-TOMCAT-001), every number traced and recounted | Posting (H2, H3) |
| HIKARI hold check: step 0 finding, pre-registration before any run, 18 new PetClinic runs, offline evaluation (see §8 AC-7.5) | Docs site (row 13) |

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

1. **After H1 (0.4.2 on Central):** run `scripts/release/sweep-install-version.sh 0.4.1 0.4.2`. It stops at the launch post's checksum line (`docs/launch/launch-post.md`), which must be updated by hand to the 0.4.2 jar's SHA-256 first.
2. **After H9 (0.4.3 via dispatch):** the first stamped release. `action-tag` in *Post-release smoke* is the only proof that a tag runs its own CLI with no `version:` (AC-7.2's last column).
3. **HIKARI S3 miss is still open.** The unchanged rule recommends INCREASE 10 → 13 in S3, where the pool is not the bottleneck.
   - P_max fails S5: a single long checkout a minute pins the decaying MAX.
   - P_share needs an estimator that reads both buckets from one snapshot, such as one `/actuator/metrics` histogram read, or the in-process `HistogramSnapshot`. It then needs a new pre-registration and fresh runs; the 2026-11-15 data cannot validate it.
   - The acquire/hold ratio stays rejected (R17).
4. R5: make `release.yml` refuse a four-part version as early as `release-commit.sh` does.
5. Gates L13: give the snapshot an age check, so that a stale snapshot fails the checklist.
6. Run actionlint on `.github/workflows/gates-collect.yml`. It was not linted in this iteration; the other three workflows were.
7. The launch checklist numbers its own human items (for example H6 is the sign-off and H7 the posting), and these numbers do not match the brief's H1–H9. Give one of them a different prefix.
8. The gates collector's first run on `github.token` (H4) should confirm `stargazers.available == true` in CI, not only locally.
