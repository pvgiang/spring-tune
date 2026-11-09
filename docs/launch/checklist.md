# Pre-post checklist

Every box is checked, with evidence (a run URL, a commit, a command output), by a named person before any launch
post goes out. One unchecked box blocks every channel. Posting itself is a human step
([iteration-5 brief §4](../delivery/iteration-5-brief.md#4-human-steps-cannot-be-done-from-this-environment), step 7).

## Blockers found while drafting (2026-11-09)

These were true when the drafts were written. Re-check each; none is optional.

- **The repository is private** (`gh api repos/pvgiang/spring-tune --jq .visibility` → `private`). Until it is
  public: the Action fails in other people's workflows, release download URLs return 404, every
  link in the post returns 404, and so do the `Docs:` links the 0.4.0 CLI prints for each finding.
- **The `v0.4.0` tag's Action defaults to the 0.1.0 CLI and does not verify a checksum** (`git show
  v0.4.0:action.yml`). *Resolved for the drafts (iteration 6): they use `@v0.4.1`, which verifies the jar against
  the release's `SHA256SUMS`, and pass `version: "0.4.1"` explicitly because the `v0.4.1` tag's own `action.yml`
  still defaults to CLI 0.4.0 ([v0.4.1 notes](../releases/v0.4.1.md)). Never claim verification for the `v0.4.0` tag.*
- **0.4.1 is not on Maven Central yet** (2026-11-09: the deployment is uploaded but not published; `curl -fsI
  https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1.pom`
  → 404). The drafts install 0.4.1 from Central, so nothing is posted until that returns 200 (iteration-6 brief §3,
  H2).
- **README and case study still say "45 of 45 … wait up to 30 s"**; precisely, 44 wait up to 30 s and 1 waits 10 s
  ([re-run, correction note](../corpus/2026-11-09-0.4.0-rerun.md#breakdown-used-by-the-launch-content)). The
  launch content is correct; the README and case study are linked from it and should match.
  *Resolved on `claude/iteration-5-adoption`: both now say 44 up to 30 s, 1 waits 10 s.*
- **The README tagline uses a word on the banned list** (row 6 of the iteration-5 brief). The check script only
  scans `docs/launch/` and `docs/deep-dives/`, but the README is the first page a reader sees from the post.
  *Resolved on `claude/iteration-5-adoption`: `scripts/content/check-claims.sh README.md` finds no banned wording.*
- **`examples/broken-config-ci/` does not exist on this branch yet** (built in parallel). The post and both deep
  dives link it. *Resolved: it is on `claude/iteration-5-adoption`, with the CI demo job.*

## Release and install

- [ ] **0.4.1 on Maven Central**: the `curl -fsI` above returns 200.
      Evidence: ______________________
- [ ] **CLI checksum in the post matches Central**: `curl -fsSL
      https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1-all.jar.sha256`
      prints the SHA-256 in the post's CLI snippet (`3f3726f7…b599e`, taken from the v0.4.1 GitHub release's
      `SHA256SUMS`; see [claims.md](claims.md#product-facts)). If it differs, use Central's value.
      Evidence: ______________________
- [ ] **Post-release smoke green for 0.4.1.** (For 0.4.0: run
      [42563268215](https://github.com/pvgiang/spring-tune/actions/runs/42563268215), `main` at `dd8fa86`,
      2026-11-09, **success**.) Run *Post-release smoke* with `0.4.1` after Central publishes it, and again on the
      day of posting if anything was republished.
      Evidence: ______________________
- [ ] **README snippets executed in CI**: the job that runs the README's Maven, Gradle (`mavenCentral()` only) and
      Action snippets in a fresh project is green on `main`, and the post's snippets are the same as the README's
      (same coordinates, same `version`, same `fail-on`).
      Evidence (run URL): ______________________
- [ ] **Demo job green**: the `examples/broken-config-ci` job fails the way it should (exit 1, the expected rule
      IDs in SARIF with line locations) on `main`, and the path in the post resolves.
      Evidence (run URL): ______________________
- [ ] **Repository public**, and an anonymous `curl -sI` of the v0.4.1 jar URL and of the post URL returns 200.
      Evidence: ______________________
- [ ] **Action ref decided**: `pvgiang/spring-tune@v0.4.1` + `version: "0.4.1"` (or a newer tag whose
      `action.yml` defaults to its own CLI); the post, variants and README use the same one, and the
      `published-action` CI job is green on `main` for it.
      Evidence: ______________________

## Content

- [ ] **Every number traced**: each number in `docs/launch/` and `docs/deep-dives/` appears in
      [claims.md](claims.md) with a committed source and a CLI version, and the line references still match.
      Evidence (reviewer): ______________________
- [ ] **Check script clean**: `scripts/content/check-claims.sh` exits 0 (banned words, install versions other than
      0.4.1).
      Evidence (output or CI run): ______________________
- [ ] **Apply never oversold**: apply appears only as experimental and opt-in, with "5 of 6 bars met, no production
      user" and the iteration-4 §2 link. The headline is the CI check and read-only advice.
      Evidence (reviewer): ______________________
- [ ] **Incidents are composites**: no named company, no invented quote; illustrative arithmetic is labeled.
      Evidence (reviewer): ______________________
- [ ] **Links work** from an anonymous browser: post, corpus report, re-run, case study, iteration-4 brief §2
      anchor, rule pages, demo, issue forms.
      Evidence: ______________________
- [ ] **Issue forms and labels ready**: `false-positive` and `rule-request` labels exist and the forms open.
      Evidence: ______________________

## Measurement

- [ ] **Baseline recorded**: the 2026-11-09 row in [gates.md](../adoption/gates.md#log) is filled, and a fresh row
      is added on the day of posting if numbers moved.
      Evidence: ______________________
- [ ] **Traffic collector able to read traffic** (fine-grained PAT stored as a secret), so referrers for the launch
      day are kept beyond GitHub's 14 days.
      Evidence (workflow run): ______________________
- [ ] **Launch-log row prepared** for each channel in [gates.md](../adoption/gates.md#launch-log); fill 24 h and
      7 d values on time.
      Evidence: ______________________

## Sign-off

| Role | Name | Date | Signature |
|---|---|---|---|
| Author (content, claims) | | | |
| PO/SRE reviewer (claims, apply wording) | | | |
| Maintainer (release, CI, repository settings) | | | |
