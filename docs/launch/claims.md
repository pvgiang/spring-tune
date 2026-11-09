# Claims trace for the launch content

Every number in [launch-post.md](launch-post.md), [variants.md](variants.md) and the
[deep dives](../deep-dives/) maps to a committed artifact here, with the CLI version that produced it. A number
that is not in this table does not go into launch content. Line numbers are as committed on 2026-11-09; the
section name is the stable reference.

Two CLI versions appear below:

- **dev build**: the 2026-11-03 corpus run; its JSON reports say `"version" : "0.1.0-SNAPSHOT"`.
- **0.4.0**: the released CLI (SHA-256 `cea94c6a…3d30`), used for the 2026-11-09 re-run at the same commits.
  The re-run is identical finding for finding, so every corpus number holds for both.

The install snippets use **0.4.1**, the current release. It has no rule changes ([v0.4.1 notes](../releases/v0.4.1.md)),
so the corpus was not re-run with it and the corpus numbers keep the version that produced them (0.4.0).

## Corpus numbers

| Claim (as worded in the content) | Used in | Source | Section / line | CLI |
|---|---|---|---|---|
| 20 public Spring Boot repositories | post, variants, deep dives | [`scripts/corpus/repos.txt`](../../scripts/corpus/repos.txt); [corpus report](../corpus/2026-11-corpus-report.md) | Method; 20 entries | dev build, 0.4.0 |
| 282 Spring Boot modules | post, r/java, HN | [corpus report](../corpus/2026-11-corpus-report.md); [re-run](../corpus/2026-11-09-0.4.0-rerun.md) | Results (final run), L33; re-run summary, L8 | dev build, 0.4.0 |
| 199 findings, 1 false positive (0.5%), reviewed by the author | post, variants | corpus report; re-run | header L4; Precision per rule, L74; re-run diff table, L58; review scope, L105 | dev build (review), 0.4.0 (unchanged) |
| First run: at least 61 of 307 findings wrong | post, variants | corpus report | header L5; "What the first run got wrong", L87 and L98 | v0 engine (PR #1) |
| 45 of 45 evaluable HikariCP modules flagged, in 12 of 20 repositories | post, variants, deep dive 1 | corpus report; re-run | Precision per rule, L66 (45, 12 repos); re-run breakdown, L76 | dev build, 0.4.0 |
| Leak detection off in all 45 | post, variants, deep dive 1 | corpus report; re-run | Precision per rule, L67; re-run breakdown, L80 | dev build, 0.4.0 |
| 44 wait up to 30 s: 38 unset, 6 set `30000`; 1 waits 10 s | post, variants, deep dive 1 | re-run, produced by [`scripts/corpus/breakdown.py`](../../scripts/corpus/breakdown.py) | Breakdown, L77–L79; correction note, L89 | 0.4.0 (identical on dev build) |
| 63 HikariCP modules detected; 18 not evaluated (11 pool built in code, 7 config outside the repo) | post, deep dive 1 | re-run | Breakdown, L75, L81–L82 | 0.4.0 |
| 25 of the 45 in two tutorial collections (ityouknow 14, xkcoding 11) | post, r/java, deep dive 1 | re-run; raw JSON `results/2026-11-09-0.4.0/{ityouknow__spring-boot-examples,xkcoding__spring-boot-demo}.json` | correction note, L94 | 0.4.0 |
| Remaining 20: FTGO 7, DolphinScheduler 5, 8 repos with 1 each | deep dive 1 | raw JSON in `results/2026-11-09-0.4.0/` (count `ST-HIKARI-001` per file) | | 0.4.0 |
| 6 HTTP clients without timeouts in 5 repositories, 0 false positives | post, variants, deep dive 2 | corpus report; re-run | Precision per rule, L72; re-run breakdown, L83 | dev build, 0.4.0 |
| 3 `new RestTemplate()`, 2 `WebClient.create(...)`, 1 `RestClient.builder().build()` | post, deep dive 2 | re-run | Breakdown, L84–L86 | 0.4.0 |
| One of the 6 is low-value (halo, dev profile only) | post, deep dive 2 | corpus report | Precision per rule, L72 (notes) | dev build |
| PetClinic gateway `ApiGatewayApplication.java:55:16`, GenAI `AIDataProvider.java:37:27` | deep dive 2 | raw JSON `results/2026-11-09-0.4.0/spring-petclinic__spring-petclinic-microservices.json`; re-run spot checks | "What was reviewed" | 0.4.0 |
| 7 executors with unbounded queues in 5 repositories | post, r/java | corpus report | Precision per rule, L71 | dev build, 0.4.0 |
| JeecgBoot `Executors.newFixedThreadPool(10)` for AI chat streams | post, r/java | corpus report; [case study](../case-study/what-we-found-in-20-spring-boot-projects.md) | Notable true positives, L80; case study §3 | dev build |
| 70 modules in 16 repositories with no transaction or query timeout | post, template | corpus report | Precision per rule, L69 | dev build, 0.4.0 |
| Pinned commits of 2026-11-03; re-run 2026-11-09 identical | post, r/java | re-run; `results/2026-11-03/shas.tsv` = `results/2026-11-09-0.4.0/shas.tsv` | What was run, L21; diff, L58 | 0.4.0 |
| DolphinScheduler API server `hikari` block without timeout or leak detection | post, deep dive 1 | case study (pinned permalink `282d956cbc`) | §1 | dev build; text output re-checked on 0.4.0 (re-run spot checks) |

## Illustrative arithmetic (labeled as such in the content)

| Claim | Used in | Source | Note |
|---|---|---|---|
| Spring Boot defaults: 200 Tomcat threads, 10 connections, `connectionTimeout` 30,000 ms | post, HN, deep dive 1 | case study §1 (L43); [ST-HIKARI-001](../rules/ST-HIKARI-001.md); [ST-TOMCAT-001](../rules/ST-TOMCAT-001.md) | library defaults (Tomcat `threads.max`, HikariCP `maximumPoolSize`, `connectionTimeout`) |
| 30 ms → 3 s: ~330/s → ~3/s served; ~47 threads blocked per second; all 200 in ~4 s | post, HN, deep dive 1 | case study §1 table, L48–L51, and its footnote L219 ("illustrative, Little's law") | not a measurement |
| Deep dive 1 timeline (~10 s health check, ~30 s restart) | deep dive 1 | composite, labeled in the text | not a measurement |
| Deep dive 2 timeline (100 req/s, a third to the dead pod, 200 threads in ~6 s) | deep dive 2 | composite, labeled in the text; 200 / (100 / 3) = 6 | not a measurement |
| Linux TCP keepalive off unless enabled; default idle time 2 hours | deep dive 2 | Linux `tcp(7)`: `tcp_keepalive_time` default 7200 s | external fact |
| HikariCP: `0` means wait forever; values below 250 ms reset to 30 s with a warning | deep dive 1 | [ST-HIKARI-001](../rules/ST-HIKARI-001.md) (0 = infinite); HikariCP `HikariConfig.setConnectionTimeout` | external fact (HikariCP source) |

## Product facts

| Claim | Used in | Source | Section / line |
|---|---|---|---|
| 0.4.0 on Maven Central, groupId `io.github.winkytek.springtune` | post, variants | [v0.4.0 release notes](../releases/v0.4.0.md); post-release smoke run [42563268215](https://github.com/pvgiang/spring-tune/actions/runs/42563268215) (success, 2026-11-09, `dd8fa86`) | Install |
| CLI SHA-256 `cea94c6a6756e9681277c17183b2a8217752e2d0e40bbd652444dc9a690ef307`, same bytes on Central and the GitHub release | post | [re-run](../corpus/2026-11-09-0.4.0-rerun.md); v0.4.0 release asset `SHA256SUMS` | What was run, L17–L18 |
| 13 rules | post, HN | [`docs/rules/`](../rules/) (13 rule pages besides README); the `rules` command of the 0.4.0 CLI lists 13, and so does the 0.4.1 CLI from the v0.4.1 GitHub release (run 2026-11-09) | |
| 0.4.1: the current release; the Action at `@v0.4.1` verifies the jar against `SHA256SUMS`, and its `action.yml` defaults to CLI 0.4.0, so the snippets pass `version: "0.4.1"` | post, variants | [v0.4.1 release notes](../releases/v0.4.1.md) | GitHub Action |
| 0.4.1 on Maven Central | post, variants | **open**: on 2026-11-09 the deployment is uploaded but not published (`springtune-check-cli-0.4.1.pom` on repo1.maven.org returns 404). Do not post before it returns 200 ([checklist](checklist.md)) | |
| CLI SHA-256 `3f3726f752ae7605ee0687ac9a1f2158585928bc593cb90b95d45e4b140b599e` (post's CLI snippet) | post | v0.4.1 GitHub release asset `SHA256SUMS`, line `springtune-cli-0.4.1.jar` (downloaded through the API 2026-11-09; the jar prints `SpringTune 0.4.1`) | **open**: that Central's `springtune-check-cli-0.4.1-all.jar` has the same bytes, as 0.4.0's did; checked after Central publishes ([checklist](checklist.md)) |
| Apply mode: 5 of 6 pre-registered bars met; bar 1 (a design partner in production) not met | post, variants, template | [iteration-4 brief §2](../delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code) | L17 (the six bars), L22, L89 ("bars 2–6 are met, bar 1 … is not"); v0.4.0 notes L51–L53 |
| Nobody runs it in production that we know of | post, variants | iteration-4 brief L22 ("cannot be met with zero users"); [gates](../adoption/gates.md) baseline (0 stars, 0 issues, 0 downloads) | |
| Three replicas raising at once within budget; failing database refuses apply | post | v0.4.0 notes, "Apply mode, proven on a real PostgreSQL" | L62 (bar 2), "Failing database (bar 3)" |
| No telemetry | post, HN | [gates](../adoption/gates.md#why-there-is-no-telemetry) | "Why there is no telemetry" |
| Default `--fail-on` is ERROR; the ERROR rules need `springtune.yml` | post | [docs/check.md](../check.md) (CLI options table); corpus report L59–L60 | |

## Install snippets: what was executed

Run on 2026-11-09 in a clean sandbox against Maven Central, with empty local repositories:

| Snippet | Command | Result |
|---|---|---|
| Maven plugin 0.4.0 (Spring Boot 3.5.16 project with a JDBC starter and `new RestTemplate()`) | `mvn verify -Dspringtune.failOn=WARNING` | exit 1: ST-HTTP-001, ST-HIKARI-001, ST-HIKARI-002 (3 at WARNING) |
| Gradle plugin 0.4.0 by id, `repositories { mavenCentral() }`, Gradle 8.14.3 | `gradle springtuneCheck` with `failOn.set("WARNING")` | exit 1: the same 3 findings |
| CLI from Central | `sha256sum` against the value above; `java -jar … --version` | `SpringTune 0.4.0`; checksum matches the release `SHA256SUMS` |
| GitHub Action, as the drafts show it now (`@v0.4.1` tag, `version` input `0.4.1`) | not executable in this sandbox | **open**: the checklist requires the `demo` and `published-action` jobs green on `main` |
| 0.4.1 CLI from the v0.4.1 GitHub release on `examples/broken-config-ci` (2026-11-09) | `check examples/broken-config-ci`; `assert-sarif.py --tool-version 0.4.1 --exact` | exit 1; the same 5 findings at the same lines as with 0.4.0 (the demo README's output) |

These sandbox runs are evidence for this table, not committed CI. The committed proof is the README-snippet CI job
and the demo job (see the [checklist](checklist.md)). Durations were not measured, so the content does not say
"two minutes"; add a duration only from a CI run and record it here.
