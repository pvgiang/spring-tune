# Iteration 1 — Delivery brief

> Source of truth for *what this iteration builds and how we prove it works*.
> Input: [business & implementation plan](../business-and-implementation-plan.md) (v2).

## 1. The goal, restated

The original ask was *"audit the initiative requirements and build the application"*. The plan
describes a 48-week, four-phase product (OSS CI check → runtime starter → adoption → paid
controller). Building all of it in one go would produce a broad, shallow, unverifiable codebase,
and the plan itself says the paid controller must wait for the Section 9 paid trigger.

**Rewritten goal for this iteration:**

> Deliver the open-source core of SpringTune as one Maven multi-module build that a Spring Boot team
> can run today:
>
> 1. **`springtune-check`** (plan Phase 1, milestones 0.1–0.3): a CI-time static analyzer for Spring
>    Boot config, Java sources, and deployment manifests that finds the configs behind
>    pool-exhaustion and hung-thread outages. It reports with severity, *why*, and *fix* as
>    text/Markdown/JSON/SARIF, and fails the build at a configurable threshold. It ships as a CLI and
>    a Maven plugin.
> 2. **`springtune-spring-boot-starter`** (plan Phase 2, milestone 0.4, *observe/advise only*): a
>    drop-in starter that exposes live knobs and signals at `/actuator/tuning` and produces
>    explainable, budget-capped recommendations. **It never changes anything.**
>
> Every rule and recommendation has a stable ID, an explanation, a fix, a docs page, and an
> automated test that proves it fires (and does not fire) on concrete inputs.

## 2. Role challenge log

The three roles reviewed the plan against each other. Each row ends with a decision.

| # | Raised by | Challenge | Response | Decision |
|---|---|---|---|---|
| 1 | PM | The plan is 48 weeks. What ships *now*? | PO: Phase 1 is the fastest path to signal, carries no runtime risk, and is a gate prerequisite. Eng: Phase 2 observe/advise reuses the same model and is safe because it is read-only. | Scope = Phase 0 + Phase 1 + Phase 2 (read-only). Controller, telemetry, and limiter are out (§4). |
| 2 | PO | "Low false positives" is the trust KPI (§11). Which design choices protect it? | Eng: (a) rules apply only when the module actually uses the technology (JDBC, Tomcat, Resilience4j, Feign); (b) unresolvable `${PLACEHOLDER}` values are *not evaluated* and are listed as diagnostics instead of guessed; (c) budget rules skip when inputs are missing and say why; (d) suppressions need a reason. | Adopt all four. Each has a test. |
| 3 | Eng | Writing our own YAML/properties merge will diverge from Spring's semantics (relaxed binding, multi-doc YAML, profile expressions, precedence). | PO: wrong precedence produces wrong findings, which kills trust. | Use Spring Boot's own `YamlPropertySourceLoader`/`PropertiesPropertySourceLoader`, `Binder`, `Profiles.of(...)`, and `PropertySourcesPlaceholdersResolver`. As a bonus, Spring's origin tracking provides file:line for SARIF. |
| 4 | PM | Gradle or Maven? The plan says "pick one". | Eng: Maven is installed and Gradle is not. Maven is also the target of the first plugin. | Maven build. The Gradle plugin is deferred, and the CLI covers Gradle users meanwhile. |
| 5 | PO | ST-TOMCAT-001 fires on *every* default app (200 threads vs 10 connections). Is that noise? | Eng: the mismatch is real, but a default the team never chose is less actionable than a value they set. | `INFO` when both values are defaults, `WARNING` when either is set explicitly. Skipped when virtual threads are on. |
| 6 | Eng | `@EnableAsync` in Boot ≥ 2.1 does **not** use `SimpleAsyncTaskExecutor` by default. Boot auto-configures `applicationTaskExecutor` with an *unbounded queue*. The plan's wording is outdated. | PO: then the rule must describe the real risk. | ST-ASYNC-001 targets "@Async on the auto-configured executor with unbounded queue" (§5). |
| 7 | PO | The connection budget needs `maxReplicas`. Where does it come from without guessing? | Eng: from an explicit `springtune.yml` entry, from a referenced Helm values or HPA file, or by auto-discovery only when exactly one candidate exists. Otherwise the rule is skipped and a diagnostic says what is missing. | Implemented as described. Never assume 1 replica. |
| 8 | PM | Runtime recommendations without load data look like guesses. | Eng: rules need a minimum number of samples over the window, and every recommendation carries its evidence (signal values) and confidence. The "don't raise the pool when connections are held long" rule is first-class, as the plan's risk table demands. | Implemented. Tested against a *real* HikariCP pool on H2 under contention. |
| 9 | Eng | Testcontainers (plan §16) needs Docker, which this environment lacks. | PO: the verification still has to be real, not mocked. | Use H2 + real HikariCP + real Micrometer. Testcontainers/Gatling suites are a follow-up (§4). |
| 10 | PO | Is `apply` mode in? | PM + Eng: the plan's trust ladder says ship levels 1–2 first. `apply` needs a revert loop and SLO verification, which cannot be proven here. | `springtune.mode` accepts `observe`/`advise` only. Any other value fails startup with a clear message. |
| 11 | PM | Publishing to Maven Central, CLA bot, docs site? | Eng: these need accounts and secrets the repo does not have. | Out of scope. The build is release-ready (sources/javadoc jars can be added with JReleaser later). |
| 12 | Eng | Shared model: is the budget formula duplicated between CI and runtime? | PO: the plan's key principle says it must be the same concept. | `ConnectionBudget` lives in `springtune-model` and is used by ST-HIKARI-004 *and* by the starter's recommendation cap. |

## 3. Scope (in)

| Module | Purpose |
|---|---|
| `springtune-model` | Shared domain: `Severity`, `Finding`, `Location`, `RuleDescriptor`, `Knob`, `Recommendation`, `ConnectionBudget` |
| `springtune-check-engine` | Project discovery, Spring-faithful config resolution, source scanner (JavaParser), deployment scanner (Helm/HPA), rule API (ServiceLoader), 13 built-in rules, suppressions, reporters |
| `springtune-check-cli` | `springtune check` (picocli), runnable fat jar |
| `springtune-maven-plugin` | `mvn springtune:check`, bound to `verify` |
| `springtune-spring-boot-autoconfigure` / `-starter` | Knob registry (HikariCP, `ThreadPoolTaskExecutor`), sampler with sliding windows, recommendation rules, `/actuator/tuning` (read-only) |
| `springtune-samples/broken-config` | Runnable Boot app with deliberately risky config; used as the end-to-end fixture |

## 4. Scope (out, with reason)

| Item | Why not now | Where it goes |
|---|---|---|
| `apply` mode, auto-revert | Trust ladder level 3, which needs SLO verification and load tests | Next iteration |
| Adaptive concurrency limiter | Independent module with no dependency from the rest | Next iteration |
| Gradle plugin, GitHub Marketplace action | Gradle not available; Marketplace requires a release | Next iteration; the CLI + SARIF upload already works in Actions (see `docs/ci-integration.md`) |
| Testcontainers / Gatling load suites | No Docker here | Next iteration |
| Telemetry, controller, UI, licensing | Phase 3/4 and gated by Section 9 signals | Per the plan |
| Kotlin sources, `spring.config.import`, env-var overrides | Cannot be known statically without more inputs | Documented limitations (`docs/check.md`) |

## 5. Acceptance criteria and how each is verified

All of these run with `mvn verify` from the repository root, unless a command is given.

| ID | Criterion | Verification |
|---|---|---|
| AC-1 | Build is green on Java 17+ with one command | `mvn -B verify` (CI: `.github/workflows/ci.yml`, JDK 17/21/25) |
| AC-2 | Config resolution matches Spring semantics: relaxed names, multi-doc YAML with `on-profile` expressions, `.properties` over `.yml`, `config/` over root, profile files over base, profile groups/include, placeholders with defaults | `ProjectConfigLoaderTest` |
| AC-3 | Each rule fires on a positive fixture and stays silent on a negative fixture | `*RuleTest` classes (one per rule family) |
| AC-4 | Unresolvable placeholders are never evaluated and appear as diagnostics | `ProjectConfigLoaderTest`, `UnresolvedPlaceholderTest` |
| AC-5 | Findings carry the file and line of the winning property (or the primary config file when the value is missing) | Golden-file test `GoldenReportTest` (`broken-config` → `expected-report.json`) |
| AC-6 | Suppression requires a reason; suppressed findings are reported but do not fail the build | `SuppressionTest` |
| AC-7 | SARIF output is valid SARIF 2.1.0 shape (schema, rules, levels, regions, suppressions) | `SarifReporterTest` |
| AC-8 | CLI exit codes: `0` = clean, `1` = findings at/above `--fail-on`, `2` = usage/config error | `CheckCommandTest` + manual `java -jar … check springtune-samples/broken-config` |
| AC-9 | Maven plugin fails the build on the broken sample and passes when `failOn=NONE` | `maven-invoker-plugin` ITs in `springtune-maven-plugin/src/it` |
| AC-10 | Starter is inert unless the matching libraries are present; `springtune.enabled=false` removes all beans; invalid `mode` fails fast | `SpringTuneAutoConfigurationTest` (`ApplicationContextRunner`) |
| AC-11 | Under real pool contention with short connection holds, the starter recommends **raising** the pool, capped by the connection budget. With long holds, it recommends **not** raising and points at slow queries/transactions | `HikariContentionIntegrationTest` (real HikariCP + H2 + Micrometer) |
| AC-12 | `/actuator/tuning` returns knobs and recommendations in a real Boot application | `BrokenConfigApplicationTest` (`@SpringBootTest`, random port) |
| AC-13 | Every rule ID has a docs page | `RuleDocsTest` |

## 6. Results

| ID | Status | Evidence |
|---|---|---|
| AC-1 | ✅ local (JDK 21, Boot 4.1.2 and 3.5.16); CI matrix for JDK 17/21/25 | `mvn -B clean verify`: 7 modules, 120 tests + 2 Maven ITs green |
| AC-2 | ✅ | `ProjectConfigLoaderTest` (16 cases) |
| AC-3 | ✅ | `HikariRulesTest` (26), `TomcatAndAsyncRulesTest` (9), `HttpAndResilienceRulesTest` (9) |
| AC-4 | ✅ | placeholder and conversion cases in `ProjectConfigLoaderTest` and `HikariRulesTest` |
| AC-5 | ✅ | `GoldenReportTest`: the two-service fixture produces the 13 hand-predicted findings, 1 suppression, 1 diagnostic |
| AC-6 | ✅ | `CheckEngineTest` (suppression, narrowing, missing reason rejected) |
| AC-7 | ✅ | `SarifReporterTest` |
| AC-8 | ✅ | `CheckCommandTest` (7 cases) and a manual fat-jar run on the fixture (exit code 1) |
| AC-9 | ✅ | invoker ITs `fails-on-risky-config` (BUILD FAILURE + SARIF) and `passes-with-failon-none` |
| AC-10 | ✅ | `SpringTuneAutoConfigurationTest` (9 cases) |
| AC-11 | ✅ | `HikariContentionIntegrationTest` (3 scenarios, stable over repeated cold runs) |
| AC-12 | ✅ | `BrokenConfigApplicationTest` (GET content, POST rejected) |
| AC-13 | ✅ | `RuleDocsTest` |

**Manual end-to-end run** (sample app with real HTTP load: 60 concurrent clients against a 20-connection pool,
budget cap 13):

- 900 ms connection holds → `ST-REC-HIKARI-002 HOLD HIGH`: "Do not raise the pool… held for 900 ms on average".
- 300 ms holds → `ST-REC-HIKARI-001 HOLD MEDIUM`: "…already above its connection budget cap of 13 (current
  20)". This run exposed an imprecise message ("at" vs "above" the cap), which was fixed and is now covered by
  `AdvisorTest`.

**Defects found by the verification and fixed:** origin unwrapping for line numbers; `Map.of` ordering making
reports non-deterministic; dependencyManagement counted as usage (false positives); non-ASCII characters
garbled on non-UTF-8 consoles; a nested `@ConditionalOnBean` that never matched; a pool-start race in the
contention test; the sample's own config sitting exactly at the ST-TOMCAT-001 threshold.

## 7. Next iteration (proposed, in plan order)

1. ~~Corpus run on 20 public Spring Boot repos and false-positive rate per rule (plan §15 exit criterion).~~ Done: [corpus report](../corpus/2026-11-corpus-report.md), 0.5% false positives after fixes.
2. ~~Gradle plugin and a GitHub Marketplace action wrapping the CLI.~~ Action shipped in 0.1.0 (`action.yml`);
   Gradle plugin done in [iteration 2](iteration-2-brief.md).
3. `apply` mode behind guardrails (bounds, step, cooldown, verification window, auto-revert), with Testcontainers PostgreSQL and Gatling load tests.
4. Adaptive concurrency limiter module.
5. Release pipeline (JReleaser → Maven Central), CLA, docs site.
