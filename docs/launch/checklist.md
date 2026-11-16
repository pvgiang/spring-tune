# Pre-post checklist

Every item has a command or a link. Items marked **L** are agent-checkable and all run from one script:

```bash
scripts/launch/verify-checklist.sh     # PASS / FAIL / NO-CHECK per item; exit 0 only if every L item passes
```

`NO-CHECK` means the item could not be checked (Maven Central answers bursts with HTTP 429, or a network error):
re-run later. It is never a pass. Items marked **HUMAN** need a person; write the evidence next to them. Posting
waits until the script exits 0 and every HUMAN item is signed. Posting itself is a human step
([iteration-7 brief §3](../delivery/iteration-7-brief.md#3-human-steps-in-order-blockers-first-none-can-be-done-from-this-environment), H2).

The posts install **0.4.1**, which is on Maven Central. They do not wait for 0.4.2.

## First, conditional

| # | Item | Command / link |
|---|---|---|
| L1 | **If 0.4.2 (or any newer release) is on Maven Central before posting, sweep the install lines first**: run `scripts/release/sweep-install-version.sh 0.4.1 0.4.2`, commit, then re-run the script. While Central returns 404 for it, the posts stay on 0.4.1 and this item passes | `v=0.4.2; curl -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/$v/springtune-check-cli-$v.pom` (404: stay on 0.4.1; 200: sweep) |

## Repository and release

| # | Item | Command / link |
|---|---|---|
| L2 | Repository is public | `gh api repos/pvgiang/spring-tune --jq .visibility` → `public` |
| L3 | Description and topics are set | `gh api repos/pvgiang/spring-tune --jq '[.description, .topics]'` |
| L4 | The CLI jar the post downloads is on Maven Central | `curl -fsI https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1-all.jar` → 200 |
| L5 | The Maven plugin in the post is on Central | `curl -fsI https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-maven-plugin/0.4.1/springtune-maven-plugin-0.4.1.pom` → 200 |
| L6 | The Gradle plugin id in the post resolves from Central (plugin marker) | `curl -fsI https://repo1.maven.org/maven2/io/github/winkytek/springtune/io.github.winkytek.springtune.gradle.plugin/0.4.1/io.github.winkytek.springtune.gradle.plugin-0.4.1.pom` → 200 |
| L7 | The SHA-256 in the post's CLI snippet equals the v0.4.1 release's `SHA256SUMS` line for `springtune-cli-0.4.1.jar` and Central's `.sha256` | [v0.4.1 release](https://github.com/pvgiang/spring-tune/releases/tag/v0.4.1); `curl -fsS https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.1/springtune-check-cli-0.4.1-all.jar.sha256` |
| L11 | The latest CI run on `main` is green, including the jobs that run the post's snippets: `README snippets: maven / gradle-portal / gradle-central / cli (Central, empty caches)`, `README snippets: pvgiang/spring-tune@v0.4.1 on the demo` (the Action as the post shows it), `Demo: examples/broken-config-ci fails as documented` and `Action: checksum verification` | [CI runs on main](https://github.com/pvgiang/spring-tune/actions/workflows/ci.yml?query=branch%3Amain) |
| HUMAN | Optional: run *Post-release smoke* with `0.4.1` (it has only ever run for 0.4.0, run [42563268215](https://github.com/pvgiang/spring-tune/actions/runs/42563268215)); the README-snippet jobs of L11 already install 0.4.1 from Central in fresh projects | [Post-release smoke](https://github.com/pvgiang/spring-tune/actions/workflows/post-release-smoke.yml) → *Run workflow* → `0.4.1` |

## Content

| # | Item | Command / link |
|---|---|---|
| L8 | Every `github.com` and `raw.githubusercontent.com` link in [launch-post.md](launch-post.md) and [variants.md](variants.md) answers HTTP 200 to an anonymous request (redirects followed; anchors not checked) | the script lists any link that does not |
| L9 | The README the post links has the one-path-per-approach layout (*Start here: pick one*, five numbered paths) on `main` | `curl -fsS https://raw.githubusercontent.com/pvgiang/spring-tune/main/README.md \| grep -E '^## (Start here\|[1-5]\. )'` |
| L10 | Claims check clean (banned wording, install versions other than 0.4.1, old groupId) | `scripts/content/check-claims.sh` |
| L12 | The labels and issue forms the post points to exist | `gh label list --search false-positive`; `gh label list --search rule-request`; [issue forms](https://github.com/pvgiang/spring-tune/tree/main/.github/ISSUE_TEMPLATE) |
| HUMAN H1 | **Every number traced and recounted**: each number in `docs/launch/` and `docs/deep-dives/` is in [claims.md](claims.md) with a committed source and a CLI version; a reviewer reruns the counting commands there | [claims.md](claims.md) |
| HUMAN H2 | **Apply never oversold**: apply appears only as experimental and opt-in, with "5 of 6 bars met, no production user" and the iteration-4 §2 link; the headline is the CI check and read-only advice; 0.4.2 is mentioned only as "released on GitHub, on Maven Central after publication" | read [launch-post.md](launch-post.md#what-it-is-not), [variants.md](variants.md) |
| HUMAN H3 | **Links and forms in an anonymous browser**: the post, the README, the corpus report, the case study, the iteration-4 brief §2 anchor, and [*New issue*](https://github.com/pvgiang/spring-tune/issues/new/choose) shows the false-positive and rule-request forms (anonymous `curl` gets a redirect to the login page, so this one is not scripted) | a private browser window |
| HUMAN H4 | **Incidents are composites**: no named company, no invented quote; illustrative arithmetic is labelled | read the post and the deep dives |

## Measurement

| # | Item | Command / link |
|---|---|---|
| L13 | The traffic collector can read traffic, so the launch-day referrers outlive GitHub's 14 days | `git fetch origin gates-data && git show origin/gates-data:snapshots.jsonl \| tail -1 \| jq .traffic.available` → `true` |
| HUMAN H5 | A [gates log](../adoption/gates.md#log) row for the day of posting (copy the latest snapshot's numbers; downloads and clones are CI-dominated and are not users), and the [launch log](../adoption/gates.md#launch-log) row per channel, with 24 h and 7 d values filled on time | [gates.md](../adoption/gates.md#launch-log) |

## Sign-off and posting

| # | Item | Command / link |
|---|---|---|
| HUMAN H6 | Sign-off below, after `scripts/launch/verify-checklist.sh` exits 0 on the day of posting (paste its last line) | the table below |
| HUMAN H7 | Post: r/java and Show HN on different days, dev.to as the long copy, a JUG or newsletter after the first feedback ([variants](variants.md)) | the post URLs go into the [launch log](../adoption/gates.md#launch-log) |

| Role | Name | Date | `verify-checklist.sh` last line | Signature |
|---|---|---|---|---|
| Author (content, claims) | | | | |
| PO/SRE reviewer (claims, apply wording) | | | | |
| Maintainer (release, CI, repository settings) | | | | |

## Run log

Each run of `scripts/launch/verify-checklist.sh` that is kept as evidence goes, verbatim, into
[`docs/adoption/launch-checklist-runs/`](../adoption/launch-checklist-runs/): the first is
[2026-11-15](../adoption/launch-checklist-runs/2026-11-15.md) (13 PASS, 0 FAIL, 0 NO-CHECK). Re-run it on the day of
posting; a past run signs nothing off.

## Resolved since the first drafts (2026-11-09)

Kept for the record; none blocks the launch any more.

- The repository was private: it is **public** (L2).
- 0.4.1 was not on Maven Central: it is, and the CLI jar's SHA-256 on Central equals the one in the post and in the
  v0.4.1 release's `SHA256SUMS` (L4, L7).
- The `v0.4.0` tag's Action defaulted to the 0.1.0 CLI without a checksum: the posts use `@v0.4.1`, which verifies <!-- sweep: keep -->
  the jar, with `version: "0.4.1"` because that tag's own `action.yml` defaults to CLI 0.4.0 <!-- sweep: keep -->
  ([v0.4.1 notes](../releases/v0.4.1.md)).
- README and case study said "45 of 45 … wait up to 30 s"; they now say 44 up to 30 s and 1 waits 10 s.
- The README tagline used a banned word; `scripts/content/check-claims.sh README.md` is clean.
- `examples/broken-config-ci/` did not exist; it does, with the CI demo job (L11).
