# Claims trace for the launch content

Every number in [launch-post.md](launch-post.md), [variants.md](variants.md) and the
[deep dives](../deep-dives/) maps to a committed artifact here, with the CLI version that produced it. A number
that is not in this table does not go into launch content. Line numbers are as committed on 2026-11-09; the
section name is the stable reference.

Two CLI versions appear below:

- **dev build**: the 2026-11-03 corpus run; its JSON reports say `"version" : "0.1.0-SNAPSHOT"`.
- **0.4.0**: the released CLI (SHA-256 `cea94c6a…3d30`), used for the 2026-11-09 re-run at the same commits.
  The re-run is identical finding for finding, so every corpus number holds for both.

The install snippets use **0.4.1**, the current release on Maven Central (0.4.2 is released on GitHub and not yet on
Central). Neither 0.4.1 nor 0.4.2 changes a check rule ([v0.4.1 notes](../releases/v0.4.1.md),
[v0.4.2 notes](../releases/v0.4.2.md)), so the corpus was not re-run and the corpus numbers keep the version that
produced them (0.4.0).

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
| 70 modules in 16 repositories with no transaction or query timeout | post, template, deep dive 3 | corpus report; re-run | Precision per rule, L69; re-run diff table, L54 | dev build, 0.4.0 |
| ST-TX-001: 1 false positive, `demo-codegen` in `xkcoding/spring-boot-demo` (own JDBC connections, no Spring-managed DataSource) | deep dive 3 | corpus report; re-run | Precision per rule, L69 (notes); re-run spot checks, L113–L114 | dev build (review), 0.4.0 (unchanged) |
| ST-TX-001 is INFO; all 70 are INFO | deep dive 3 | [rule page](../rules/ST-TX-001.md); raw JSON (`severity` of every ST-TX-001 finding) | | 0.4.0 |
| 42 of the 70 in two tutorial collections (ityouknow 25, xkcoding 17) | deep dive 3 | raw JSON `results/2026-11-09-0.4.0/{ityouknow__spring-boot-examples,xkcoding__spring-boot-demo}.json` (ST-TX-001 per file; command below) | | 0.4.0 |
| Other 28 in 14 repositories: FTGO 7, DolphinScheduler 5, mall 4, JeecgBoot 2, 10 repositories with 1 each | deep dive 3 | raw JSON in `results/2026-11-09-0.4.0/` (ST-TX-001 per file; command below) | | 0.4.0 |
| 54 of the 70 modules use HikariCP (43 also flagged by ST-HIKARI-001, 11 pool built in code); 16 without HikariCP detected | deep dive 3 | raw JSON: `modules[].technologies` contains `hikari`, ST-HIKARI-001 findings per module, and the "builds its own DataSource" *not evaluated* diagnostic (command below); the 11 match the re-run breakdown L81 | | 0.4.0 |
| 43 of the 45 ST-HIKARI-001 modules also have ST-TX-001 | deep dive 3 | raw JSON (modules with both findings; command below) | | 0.4.0 |
| 45 modules *not evaluated* by missing-property rules (config outside the repository or relocated in code) | deep dive 3 | re-run; raw JSON `notEvaluated[]` with code `UNSUPPORTED` ("Local config is incomplete": 16 Config Server, 16 `spring.config.name`/`location` in code, 7 remote `spring.config.import`, 6 Nacos) | Diff, L62 (45 `UNSUPPORTED`) | 0.4.0 |
| Spring PetClinic `application.properties` sets no transaction or query timeout (JPA) | deep dive 3 | raw JSON `results/2026-11-09-0.4.0/spring-projects__spring-petclinic.json` (ST-TX-001 at `src/main/resources/application.properties:1`); file at the pinned commit `500158f7` (`shas.tsv`), linked in the text | | 0.4.0 |
| Pinned commits of 2026-11-03; re-run 2026-11-09 identical | post, r/java | re-run; `results/2026-11-03/shas.tsv` = `results/2026-11-09-0.4.0/shas.tsv` | What was run, L21; diff, L58 | 0.4.0 |
| DolphinScheduler API server `hikari` block without timeout or leak detection | post, deep dive 1 | case study (pinned permalink `282d956cbc`) | §1 | dev build; text output re-checked on 0.4.0 (re-run spot checks) |

Deep dive 3's per-repository and HikariCP counts, from the raw JSON (Python 3, repository root):

```bash
python3 -I - docs/corpus/results/2026-11-09-0.4.0 <<'PY'
import collections, json, pathlib, sys
per_repo, hikari, both_h1, code_built, h1_total = collections.Counter(), 0, 0, 0, 0
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    r = json.loads(f.read_text()); fs = [x for x in r["findings"] if not x["suppressed"]]
    tx = {x["module"] for x in fs if x["ruleId"] == "ST-TX-001"}
    h1 = {x["module"] for x in fs if x["ruleId"] == "ST-HIKARI-001"}
    own = {d["module"] for d in r["notEvaluated"] if "builds its own DataSource" in d["message"]}
    per_repo[f.stem] += len(tx); h1_total += len(h1); both_h1 += len(tx & h1)
    hikari += sum(1 for m in r["modules"] if m["path"] in tx and "hikari" in m["technologies"])
    code_built += len(tx & own)
print(sum(per_repo.values()), "findings,", sum(1 for v in per_repo.values() if v), "repos;", +per_repo)
print("HikariCP:", hikari, "| also ST-HIKARI-001:", both_h1, "of", h1_total, "| pool built in code:", code_built)
PY
```

Output on 2026-11-09: 70 findings in 16 repositories (ityouknow 25, xkcoding 17, FTGO 7, DolphinScheduler 5,
mall 4, JeecgBoot 2, ten with 1); HikariCP 54, also ST-HIKARI-001 43 of 45, pool built in code 11.

## Deep dive #4 (ST-TOMCAT-001)

[Deep dive #4](../deep-dives/ST-TOMCAT-001-tomcat-threads-vs-pool.md), dated 2026-11-15. Corpus numbers from the raw
JSON of the 0.4.0 re-run (`docs/corpus/results/2026-11-09-0.4.0/`), counted with the command below; rule behaviour
from [`TomcatThreadsVsPoolRule.java`](../../springtune-check-engine/src/main/java/io/github/winkytek/springtune/check/rules/TomcatThreadsVsPoolRule.java),
`ModuleModel.usesTomcat()` / `usesVirtualThreads()` / `usesBootManagedHikari()` and
`TomcatAndAsyncRulesTest` / `CorpusRegressionTest.legacyTomcatMaxThreadsKeyIsRead` at this commit (no rule changes
since 0.4.0).

| Claim (as worded in the content) | Source | CLI |
|---|---|---|
| 25 modules in 7 repositories, 0 false positives, all INFO, all the same message (200 default threads vs 10 default connections, 20:1, threshold 10:1) | corpus report, Precision per rule (ST-TOMCAT-001 row: 25, 7, 0, "All INFO"); re-run diff table (25 same); raw JSON (command below) | dev build (review), 0.4.0 |
| No module in the corpus set a mismatch of its own; every finding is the default case | corpus report, "No explicit Tomcat/pool mismatch in the corpus"; raw JSON (`severity` INFO for all 25) | dev build, 0.4.0 |
| 16 of 25 in the tutorial collections (ityouknow 11, xkcoding 5); other 9: FTGO 5, RealWorld 1, JHipster sample 1, PetClinic REST 1, PetClinic 1 | raw JSON (command below) | 0.4.0 |
| All 25 also flagged by ST-HIKARI-001 | raw JSON (modules with both findings; command below) | 0.4.0 |
| 38 modules in which both HikariCP and embedded Tomcat were detected, in 10 repositories; 13 not flagged | raw JSON: `modules[].technologies` contains `hikari` and `tomcat` (command below) | 0.4.0 |
| 5 of the 13: DataSource built in code; 4: config in a Config Server (PetClinic microservices customers, vets, visits, GenAI) | raw JSON `notEvaluated[]` of those modules ("builds its own DataSource", "Local config is incomplete: spring.config.import loads remote configuration") | 0.4.0 |
| 4 of the 13 inside the ratio: pool 20 in `xkcoding` `demo-orm-jdbctemplate` and `demo-task-quartz` and ShenYu `shenyu-admin`, pool 30 in JeecgBoot `jeecg-cloud-xxljob`; the three at 20 have `connection-timeout` 30000 | by elimination in the command below (no *not evaluated* note, no finding), then the files at the pinned commits (`shas.tsv`): `demo-orm-jdbctemplate/src/main/resources/application.yml` L21, `demo-task-quartz/src/main/resources/application.yml` L15 (`connection-timeout: 30000` L20), `shenyu-admin/src/main/resources/application-h2.yml` L31 (L29), `jeecg-cloud-xxljob/src/main/resources/application.properties` L32 (`maximum-pool-size=30`), read 2026-11-15 from raw.githubusercontent.com; ST-HIKARI-001 messages in the raw JSON ("connectionTimeout is 30000 ms" for the three) | 0.4.0 |
| 25 other HikariCP modules where embedded Tomcat was not detected | raw JSON (command below); 38 + 25 = 63, the HikariCP modules detected in the re-run breakdown | 0.4.0 |
| Spring PetClinic sets neither value (finding at `application.properties:1`) | raw JSON `results/2026-11-09-0.4.0/spring-projects__spring-petclinic.json`; file at the pinned commit `500158f7`, linked in the text | 0.4.0 |
| Defaults 200 threads, 10 connections; legacy key `server.tomcat.max-threads`; `max-ratio` default 10; skipped with virtual threads, Jetty or Undertow; unresolved placeholder: no finding; INFO when both default (at the config file, line 1), WARNING when either is set (at the line that sets it) | rule source (`DEFAULT_THREADS_MAX`, `LEGACY_THREADS_MAX`, `DEFAULT_MAX_RATIO`, `isUnresolved`, `fallbackLocation`); tests `tomcatDefaultsAreInfo`, `tomcatExplicitMismatchIsWarningAtThreadsLine`, `tomcatSkippedWithVirtualThreadsOrJetty`, `legacyTomcatMaxThreadsKeyIsRead`; [rule page](../rules/ST-TOMCAT-001.md) | source at this commit |
| Demo: `threads.max: 400` vs pool 10, WARNING, 40:1, `application.yml:15:12` | [`docs/readme-runs/check-runs.txt`](../readme-runs/check-runs.txt) (0.4.1 capture); [demo README](../../examples/broken-config-ci/README.md) | 0.4.1 |
| `rules.<id>.options` syntax for `max-ratio` | [configuration reference](../configuration.md) (`rules:` block) | |

Counting command (Python 3, repository root):

```bash
python3 -I - docs/corpus/results/2026-11-09-0.4.0 <<'PY'
import collections, json, pathlib, sys
per_repo, sev, msg, why = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
considered, repos, also_h1, no_tomcat, within = 0, set(), 0, 0, []
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    r = json.loads(f.read_text()); fs = [x for x in r["findings"] if not x["suppressed"]]
    tc = {x["module"]: x for x in fs if x["ruleId"] == "ST-TOMCAT-001"}
    h1 = {x["module"] for x in fs if x["ruleId"] == "ST-HIKARI-001"}
    for x in tc.values(): per_repo[f.stem] += 1; sev[x["severity"]] += 1; msg[x["message"]] += 1
    also_h1 += len(set(tc) & h1)
    for m in r["modules"]:
        t = set(m["technologies"])
        if "hikari" not in t: continue
        if "tomcat" not in t: no_tomcat += 1; continue
        considered += 1; repos.add(f.stem)
        if m["path"] in tc: continue
        notes = " ".join(n["message"] for n in r["notEvaluated"] if n["module"] == m["path"])
        if "builds its own DataSource" in notes: why["DataSource built in code"] += 1
        elif "Local config is incomplete" in notes: why["config outside the repository"] += 1
        else: why["inside the ratio"] += 1; within.append(f.stem + ":" + m["path"])
print(sum(per_repo.values()), "findings,", len(per_repo), "repos;", dict(sev), "| also ST-HIKARI-001:", also_h1)
print(dict(per_repo.most_common())); print(dict(msg))
print("HikariCP + Tomcat:", considered, "modules in", len(repos), "repos; not flagged:", dict(why), within)
print("HikariCP without Tomcat detected:", no_tomcat)
PY
```

Output on 2026-11-15: 25 findings, 7 repos, all INFO, also ST-HIKARI-001 25; ityouknow 11, FTGO 5, xkcoding 5,
RealWorld 1, JHipster sample 1, PetClinic REST 1, PetClinic 1; one message (200 default vs 10 default, 20:1);
HikariCP + Tomcat 38 modules in 10 repos, not flagged: inside the ratio 4, DataSource built in code 5, config outside
the repository 4 (the four inside the ratio as listed above); HikariCP without Tomcat detected 25.

## Illustrative arithmetic (labeled as such in the content)

| Claim | Used in | Source | Note |
|---|---|---|---|
| Spring Boot defaults: 200 Tomcat threads, 10 connections, `connectionTimeout` 30,000 ms | post, HN, deep dive 1 | case study §1 (L43); [ST-HIKARI-001](../rules/ST-HIKARI-001.md); [ST-TOMCAT-001](../rules/ST-TOMCAT-001.md) | library defaults (Tomcat `threads.max`, HikariCP `maximumPoolSize`, `connectionTimeout`) |
| 30 ms → 3 s: ~330/s → ~3/s served; ~47 threads blocked per second; all 200 in ~4 s | post, HN, deep dive 1 | case study §1 table, L48–L51, and its footnote L219 ("illustrative, Little's law") | not a measurement |
| Deep dive 1 timeline (~10 s health check, ~30 s restart) | deep dive 1 | composite, labeled in the text | not a measurement |
| Deep dive 2 timeline (100 req/s, a third to the dead pod, 200 threads in ~6 s) | deep dive 2 | composite, labeled in the text; 200 / (100 / 3) = 6 | not a measurement |
| Deep dive 3 timeline (export from 2 s to ~4 min, 10 exports hold all 10 connections, 30 s waits) | deep dive 3 | composite, labeled in the text; pool size and `connectionTimeout` are the Spring Boot/HikariCP defaults above | not a measurement |
| Spring Boot sets no transaction or query timeout by default; `jakarta.persistence.query.timeout` is in milliseconds; `@Transactional(timeout)` in seconds | deep dive 3 | Spring Boot (`spring.transaction.default-timeout`, `spring.jdbc.template.query-timeout` have no default), JPA specification (query timeout hint in ms), Spring `@Transactional` Javadoc (seconds) | external fact |
| Linux TCP keepalive off unless enabled; default idle time 2 hours | deep dive 2 | Linux `tcp(7)`: `tcp_keepalive_time` default 7200 s | external fact |
| Deep dive 4 timeline (100 req/s, 40 need the database, 20 ms → 2 s; 10 / 2 = 5 served per second, 35 per second park; 190 / 35 ≈ 5.4 s to fill 200 threads; probe fails ~10 s; first timeouts at 30 s) and its fixed variant (pool 20 at 2 s = 10 per second; `connectionTimeout` 1 s; FIFO handoff, so all 40 per second wait ≈ 1 s: 40 parked; 100 − 20 − 40 = 40 threads left; with 2 s it would be 80 parked, 0 left) | deep dive 4 | composite, labeled in the text; defaults and timeout from the rows above; FIFO: HikariCP `ConcurrentBag` hands off through `new SynchronousQueue<>(true)` (fair), checked in HikariCP 6.3.3 bytecode; a 1 ms step simulation of the variant gives 59.9 threads in use (FIFO) at 1 s, 99.9 at 2 s | not a measurement |
| `management.server.port` runs the actuator on a separate embedded server; `server.tomcat.accept-count` is Tomcat's queue when all threads are busy, connections beyond it are refused; HikariCP throws `SQLTransientConnectionException` after `connectionTimeout` | deep dive 4 | Spring Boot reference (actuator *Customizing the Management Server Port*; `server.tomcat.accept-count`), Tomcat HTTP connector docs (`acceptCount`), HikariCP README (`connectionTimeout`) | external fact |
| HikariCP: `0` means wait forever; values below 250 ms reset to 30 s with a warning | deep dive 1 | [ST-HIKARI-001](../rules/ST-HIKARI-001.md) (0 = infinite); HikariCP `HikariConfig.setConnectionTimeout` | external fact (HikariCP source) |

## Product facts

| Claim | Used in | Source | Section / line |
|---|---|---|---|
| 0.4.0 on Maven Central, groupId `io.github.winkytek.springtune` | post, variants | [v0.4.0 release notes](../releases/v0.4.0.md); post-release smoke run [42563268215](https://github.com/pvgiang/spring-tune/actions/runs/42563268215) (success, 2026-11-09, `dd8fa86`) | Install |
| CLI SHA-256 `cea94c6a6756e9681277c17183b2a8217752e2d0e40bbd652444dc9a690ef307`, same bytes on Central and the GitHub release | post | [re-run](../corpus/2026-11-09-0.4.0-rerun.md); v0.4.0 release asset `SHA256SUMS` | What was run, L17–L18 |
| 13 rules | post, HN | [`docs/rules/`](../rules/) (13 rule pages besides README); the `rules` command of the 0.4.0 CLI lists 13, and so does the 0.4.1 CLI from the v0.4.1 GitHub release (run 2026-11-09) | |
| 0.4.1: the current release; the Action at `@v0.4.1` verifies the jar against `SHA256SUMS`, and its `action.yml` defaults to CLI 0.4.0, so the snippets pass `version: "0.4.1"` | post, variants | [v0.4.1 release notes](../releases/v0.4.1.md) | GitHub Action <!-- sweep: keep --> |
| 0.4.1 on Maven Central (CLI jar, Maven plugin, Gradle plugin marker) | post, variants | `scripts/launch/verify-checklist.sh` items L4–L6, run 2026-11-15: HTTP 200 for `springtune-check-cli-0.4.1-all.jar`, `springtune-maven-plugin-0.4.1.pom`, `io.github.winkytek.springtune.gradle.plugin-0.4.1.pom` on repo1.maven.org | [checklist](checklist.md) L4–L6 <!-- sweep: keep --> |
| CLI SHA-256 `3f3726f752ae7605ee0687ac9a1f2158585928bc593cb90b95d45e4b140b599e` (post's CLI snippet) | post | v0.4.1 GitHub release asset `SHA256SUMS`, line `springtune-cli-0.4.1.jar`, **and** Central's `springtune-check-cli-0.4.1-all.jar.sha256`: equal (`verify-checklist.sh` L7, 2026-11-15) | [checklist](checklist.md) L7 <!-- sweep: keep --> |
| The repository is public; README has one path per approach (*Start here: pick one*, five numbered sections) | post, variants | `gh api repos/pvgiang/spring-tune --jq .visibility` → `public` (2026-11-15); README on `main` (`verify-checklist.sh` L2, L9) | |
| 0.4.2 adds a per-rule status to `/actuator/tuning` and a pasteable report; released on GitHub, **not** on Maven Central (only ever worded as "on Maven Central after publication") | post | [v0.4.2 release notes](../releases/v0.4.2.md), "Runtime starter: what a first user sees"; `curl -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/$v/` with `v=0.4.2` → 404 on 2026-11-15 ([checklist](checklist.md) L1) | |
| Apply mode: 5 of 6 pre-registered bars met; bar 1 (a design partner in production) not met | post, variants, template | [iteration-4 brief §2](../delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code) | L17 (the six bars), L22, L89 ("bars 2–6 are met, bar 1 … is not"); v0.4.0 notes L51–L53 |
| Nobody runs it in production that we know of | post, variants | iteration-4 brief L22 ("cannot be met with zero users"); [gates](../adoption/gates.md#log): 0 issues, 0 Action users, 0 design partners; the 2 stars are the maintainer's own accounts (2026-11-15) | |
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
| GitHub Action, as the drafts show it now (`@v0.4.1` tag, `version` input `0.4.1`) | CI job `README snippets: pvgiang/spring-tune@v0.4.1 on the demo` | success on `main` at `6b3fa10`, run [43704573116](https://github.com/pvgiang/spring-tune/actions/runs/43704573116) (2026-11-15), with the `Demo:` and the four Central `README snippets:` jobs ([checklist](checklist.md) L11) <!-- sweep: keep --> |
| 0.4.1 CLI from the v0.4.1 GitHub release on `examples/broken-config-ci` (2026-11-09) | `check examples/broken-config-ci`; `assert-sarif.py --tool-version 0.4.1 --exact` | exit 1; the same 5 findings at the same lines as with 0.4.0 (the demo README's output) |

These sandbox runs are evidence for this table, not committed CI. The committed proof is the README-snippet CI job
and the demo job (see the [checklist](checklist.md)). Durations were not measured, so the content does not say
"two minutes"; add a duration only from a CI run and record it here.
