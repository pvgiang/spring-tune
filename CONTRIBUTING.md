# Contributing to SpringTune

Thanks for helping. The most useful contributions right now, in order:

1. **A false positive**: a finding that is wrong for your project. Use the
   [false positive form](https://github.com/pvgiang/spring-tune/issues/new?template=1-false-positive.yml).
2. **A rule request**: a risky Spring Boot setting that bit you in production. Use the
   [rule request form](https://github.com/pvgiang/spring-tune/issues/new?template=2-rule-request.yml).
3. **Recommendation feedback** from the runtime starter (applied, edited, rejected, and why).
4. Code: fixes, new rules, docs. For anything bigger than a small fix, open an issue first so we agree on the
   approach before you spend time on it.

Every issue goes through a form, because the labels they set are how we decide what to build next
([adoption gates](docs/adoption/gates.md)). Security problems go through [SECURITY.md](SECURITY.md), never a public
issue. Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Build and test

JDK 17+ and Maven 3.9+. CI ([`ci.yml`](.github/workflows/ci.yml)) runs all of these.

```bash
mvn -B verify                                               # all modules: unit + integration tests, Maven plugin ITs
mvn -B verify -Dinvoker.skip                                # faster: skip the Maven plugin integration tests (invoker)
mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip   # Spring Boot 3.5 compatibility build (starter, sample)
```

The Gradle plugin's functional tests run real Gradle builds. They use `-Dspringtune.it.gradle=<gradle binary or
GRADLE_HOME>`, else `gradle` on `PATH`, and are skipped without one:

```bash
mvn -B verify -Dinvoker.skip -pl springtune-gradle-plugin-tests -am \
  -Dspringtune.it.requireGradle=true -Dspringtune.it.gradle="$(command -v gradle)"
```

The **load suite** (apply mode against a real PostgreSQL) is opt-in: a normal `mvn verify` compiles it and runs
nothing. It needs Docker (Testcontainers) or an external server; see [docs/loadtests.md](docs/loadtests.md#running-them):

```bash
mvn -B -ntp verify -pl springtune-loadtests -am -DskipITs -Dinvoker.skip -Dspringtune.it.requireDocker=true
```

Try the check on the deliberately broken sample:

```bash
mvn -q install -DskipTests -Dinvoker.skip
java -jar springtune-check-cli/target/springtune-check-cli-*-all.jar check springtune-samples/broken-config
```

## Every false positive becomes a regression test

A rule that cries wolf gets switched off, and then it catches nothing. So a confirmed false positive is fixed
**with a test that reproduces it**, reduced to the smallest config or source that triggered it, before (or in the
same pull request as) the fix. Corpus false positives live in
[`CorpusRegressionTest`](springtune-check-engine/src/test/java/io/github/winkytek/springtune/check/rules/CorpusRegressionTest.java),
each with a comment naming where it was found; per-rule cases go in that rule's test class
(`HikariRulesTest`, `HttpAndResilienceRulesTest`, `TomcatAndAsyncRulesTest`). The fix may make the rule quieter;
it must not make the test pass by deleting the case.

## Adding a rule

1. **ID and title.** `ST-<AREA>-<NNN>`, next free number in its area (see [docs/rules/README.md](docs/rules/README.md)).
   IDs are stable forever: they appear in SARIF, suppressions and users' `springtune.yml`. A rule that is
   withdrawn keeps its ID retired; it is never reused.
2. **Code.** Implement `io.github.winkytek.springtune.check.rules.Rule` in
   `springtune-check-engine/src/main/java/io/github/winkytek/springtune/check/rules/` and register it in
   `src/main/resources/META-INF/services/io.github.winkytek.springtune.check.rules.Rule` (the engine loads rules with
   `ServiceLoader`). Default severity: `ERROR` only when the setting is wrong in every deployment; otherwise
   `WARNING` or `INFO`.
3. **Fixture and tests.** A failing case (with the expected line location), a passing case, and the cases that must
   *not* fire (the setting is safe, overridden in another profile, or owned by a different library). Add the finding
   to the `shop` fixture's expected report if the rule fires there.
4. **Docs page.** `docs/rules/<ID>.md`, starting with `# <ID>: <title>` and with the sections `## Why it matters`,
   `## Example` and `## Suppress`; add a row to the table in `docs/rules/README.md`.
   [`RuleDocsTest`](springtune-check-engine/src/test/java/io/github/winkytek/springtune/check/RuleDocsTest.java)
   fails the build if any of this is missing.
5. **Evidence.** Say in the pull request where the risk comes from (an incident, library docs, a default that
   changed) and, if you can, run it on a few real projects and report what it found.

## Pull requests

- Keep them focused; one change per pull request. Run `mvn -B verify` before pushing.
- Match the existing style; no new runtime dependencies without discussing them first (the starter and the engine
  run inside other people's applications and builds).
- Update the docs that describe what you changed. Numbers in docs must point at a committed artifact
  (a report, a benchmark JSON, a test).

## Sign your commits (DCO)

SpringTune uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO), not a contributor
license agreement. Add a `Signed-off-by` line to every commit:

```bash
git commit -s -m "Fix ST-HIKARI-001 false positive on Druid pools"
# Signed-off-by: Your Name <you@example.com>  (from git config user.name / user.email)
```

By signing off you state that you wrote the change, or otherwise have the right to submit it, under the project's
license ([Apache 2.0](LICENSE)). Forgot it? `git commit --amend -s` for the last commit, or
`git rebase --signoff main` for a branch.

**Why DCO and not a CLA:** a CLA is a legal document to read, sometimes to get approved by your employer, before a
first one-line fix; it deters exactly the contributor we most want. The DCO is one line per commit and keeps
copyright with you. Contributions to this repository stay Apache 2.0. If a commercial component is ever built (for
example a fleet controller), it will be separate code and will not relicense what you contributed here.
