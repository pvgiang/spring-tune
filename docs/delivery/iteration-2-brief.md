# Iteration 2: Delivery brief (Gradle plugin)

> Follows [iteration 1](iteration-1-brief.md), released as [v0.1.0](../releases/v0.1.0.md). Input: its §7 item 2
> and the [plan](../business-and-implementation-plan.md) §15 DoD ("Maven/Gradle plugins"), milestone 0.3.

## 1. Goal

> Gradle teams can add SpringTune in one `plugins { }` line: `./gradlew check` fails on risky Spring Boot
> configuration, with the same rules, IDs, reports and `springtune.yml` as the CLI and Maven plugin. It must not
> destabilize the build it is added to, and must not see *less* than the CLI does.

The GitHub Action half of §7 item 2 shipped with 0.1.0 (`action.yml`). Listing it on the Marketplace is a manual
step in the GitHub release UI and needs no code.

## 2. Challenge log

| # | Challenge | Decision |
|---|---|---|
| 1 | Iteration 1 deferred Gradle because it was not installed. Should the plugin get its own Gradle build? | No. Build it in the Maven reactor against `dev.gradleplugins:gradle-api` (compile-only), so there is one version, one release and one CI. Prove it with real Gradle builds instead of mocks (§4). |
| 2 | The engine brings Spring Boot 4.1, Jackson and JavaParser. On the buildscript classpath, Gradle would conflict-resolve them against the Spring Boot Gradle plugin's own versions (e.g. Boot 3.5) and could break it. | The plugin jar has **no runtime dependencies**. The engine is resolved through a `springtune` configuration and runs in a `classLoaderIsolation` worker, the same pattern as Checkstyle and SpotBugs. Tested next to the Boot 3.5 Gradle plugin. |
| 3 | The corpus showed Gradle builds with version catalogs as `? (Gradle)`: the literal reader cannot see `libs.spring.boot.starter.jdbc`, so JDBC rules silently do not run. A Gradle plugin that only wraps the CLI keeps that blind spot. | The plugin passes the **resolved `runtimeClasspath`** and Spring Boot version to the engine (`ResolvedBuild`, merged over what the build files say). That is also what Boot's auto-configuration sees. |
| 4 | Resolution can fail (offline, rate-limited repository). Gradle then records an unresolved node instead of throwing, so the check would silently see less. Found in testing, when Maven Central rate-limited the container. | The requested module name is still used (it is a fact), and the task **warns** which dependencies did not resolve. Covered by a test. |
| 5 | Up-to-date checks and the build cache: can `springtuneCheck` be skipped when inputs are unchanged? | Not safely. The engine reads parent build files, in-repo libraries and Helm values anywhere below the root. The task declares `doNotTrackState` with that reason, because a wrong UP-TO-DATE hides findings. |
| 6 | Users resolve the plugin by id. Gradle needs a plugin marker artifact, and resolving it pulled in `springtune-parent` and every BOM it imports (dozens of POMs). | Marker module `io.github.winky.springtune.gradle.plugin`, plus **flattened consumer POMs** (no parent, no BOM imports) for model, engine, plugin and marker. |
| 7 | Gradle Plugin Portal / Maven Central publishing? | Out of scope: it needs accounts and keys. The release's Maven repository zip now also contains the plugin and marker. |

## 3. Scope

| In | Out (where it goes) |
|---|---|
| `springtune-gradle-plugin`: plugin `io.github.winky.springtune`, `springtune { }` extension, `-Pspringtune.*` overrides, `springtuneCheck` wired into `check`, isolated engine worker | Plugin Portal / Central publishing (release pipeline item, plan §13) |
| Plugin marker; flattened POMs | Re-running the 20-repo corpus through the plugin (follow-up: the Gradle repos need their own builds to resolve) |
| `ResolvedBuild` in the engine (`CheckRequest.resolvedBuilds`) | Kotlin DSL type-safe accessors beyond the generated defaults |
| Functional tests on real Gradle; CI job per Gradle version | |

## 4. Acceptance criteria and evidence

Functional tests (`springtune-gradle-plugin-tests`) copy a fixture build, then run a real Gradle installation
that resolves the plugin **by id** from a Maven repository staged by `invoker:install`, as a user's build would.
Builds run with `--warning-mode=fail`, so deprecations in the plugin fail the tests, except the fixture that
applies the Spring Boot 3.5 Gradle plugin, which uses a Gradle 9 deprecated API itself.

| ID | Criterion | Verification | Result |
|---|---|---|---|
| AC-G1 | Dependencies declared only through a version catalog enable the matching rules, and the report shows the resolved Boot version | `ResolvedBuildTest` (engine); `versionCatalogBuildFails…` (Gradle) | ✅ |
| AC-G2 | Works next to the Spring Boot Gradle plugin (different Boot version than the engine's) | same fixture applies `org.springframework.boot` 3.5.16 | ✅ |
| AC-G3 | Fails the build at the threshold with the finding, `file:line` and fix; writes Markdown/SARIF/JSON | `versionCatalogBuildFails…` | ✅ |
| AC-G4 | Configuration cache: stored with 0 problems, and reused on the second run with the same result | same test, two `--configuration-cache` runs | ✅ |
| AC-G5 | `check` depends on `springtuneCheck` | `checkLifecycleRunsSpringtuneCheck` | ✅ |
| AC-G6 | Multi-project: root `springtune.yml` (suppression with reason) applies, the DSL `failOn` blocks, and `-Pspringtune.failOn` overrides it; SARIF paths are relative to the root project; profile files win; library projects are skipped | `multiProjectBuild…` | ✅ |
| AC-G7 | Unresolvable dependencies produce a warning and still count by requested name | `unresolvedDependencies…` | ✅ |
| AC-G8 | Gradle versions | Local: 8.5, 8.14.3, 9.8.2 (all 4 tests each). CI: 8.14.3 and 9.8.2 | ✅ |
| AC-G9 | Nothing regresses | `mvn -B clean verify` (all 11 modules, including the Maven plugin ITs) and the Boot 3.5 compatibility build | ✅ |

**Defects found by the verification and fixed:** unresolved dependencies silently dropped (challenge 4); the
plugin's own `Configuration.setVisible` call is deprecated in Gradle 9 and removed in 11; resolving the plugin
fetched every BOM in `springtune-parent` (challenge 6); inherited POM URLs had the artifactId appended; CI
pinned the CLI jar name to `0.1.0-SNAPSHOT`.

## 5. Next (in plan order)

1. Release 0.2.0 (`docs/releases/v0.2.0.md` is drafted), then publish to the Gradle Plugin Portal and Maven
   Central (JReleaser), so the `repositories { maven(file…) }` step goes away.
2. Re-measure the corpus's Gradle projects through the plugin. They were the blind spot of the literal build
   reader.
3. `apply` mode behind guardrails, with Testcontainers PostgreSQL and Gatling load tests (plan §16).
4. Adaptive concurrency limiter module.
