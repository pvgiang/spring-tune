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
  v0.4.0:action.yml`). The drafts therefore pass `version: "0.4.0"` explicitly and do not claim the Action
  verifies the jar. If a new tag ships the checksum-verifying Action, update `uses:` in the post and variants
  (and `scripts/content/check-claims.sh` if the version changes), then you may say it verifies the jar.
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

- [ ] **Post-release smoke green for 0.4.0.** Run
      [42563268215](https://github.com/pvgiang/spring-tune/actions/runs/42563268215) (`Post-release smoke`,
      `main` at `dd8fa86`, 2026-11-09): **success** when checked on 2026-11-09. Re-run it on the day of posting if
      anything was republished.
      Evidence: ______________________
- [ ] **README snippets executed in CI**: the job that runs the README's Maven, Gradle (`mavenCentral()` only) and
      Action snippets in a fresh project is green on `main`, and the post's snippets are the same as the README's
      (same coordinates, same `version`, same `fail-on`).
      Evidence (run URL): ______________________
- [ ] **Demo job green**: the `examples/broken-config-ci` job fails the way it should (exit 1, the expected rule
      IDs in SARIF with line locations) on `main`, and the path in the post resolves.
      Evidence (run URL): ______________________
- [ ] **Repository public**, and an anonymous `curl -sI` of the v0.4.0 jar URL and of the post URL returns 200.
      Evidence: ______________________
- [ ] **Action ref decided**: `pvgiang/spring-tune@v0.4.0` + `version: "0.4.0"`, or a newer tag with checksum
      verification; the post, variants and README use the same one.
      Evidence: ______________________

## Content

- [ ] **Every number traced**: each number in `docs/launch/` and `docs/deep-dives/` appears in
      [claims.md](claims.md) with a committed source and a CLI version, and the line references still match.
      Evidence (reviewer): ______________________
- [ ] **Check script clean**: `scripts/content/check-claims.sh` exits 0 (banned words, install versions other than
      0.4.0).
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
