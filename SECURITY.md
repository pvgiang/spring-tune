# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.4.x | Yes: fixes ship as a new 0.4.x patch release |
| < 0.4 | No: upgrade to the latest 0.4.x (0.1.0 to 0.3.0 were GitHub-only and use an older groupId) |

SpringTune is pre-1.0. Once 0.5.0 is out, only the latest minor line gets security fixes.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a vulnerability.**

Report it privately through GitHub: on the repository, open **Security → Report a vulnerability**
([direct link](https://github.com/pvgiang/spring-tune/security/advisories/new)). This creates a private advisory that
only you and the maintainer can see, and lets us work on a fix and a CVE together.

> If that link says private vulnerability reporting is not enabled, the maintainer has not switched it on yet
> (*Settings → Code security → Private vulnerability reporting*). Until then, open a public issue that says only
> "security contact needed", with no details, and you will be given a private channel.

Please include the SpringTune version, how it runs (CLI, Maven plugin, Gradle plugin, GitHub Action, starter and its
`springtune.mode`), the Spring Boot and Java versions, and steps or a minimal project that reproduces it.

## Scope

In scope:

- **The check on build machines**: the CLI (`springtune-check-cli`), the Maven plugin, the Gradle plugin and the
  GitHub Action (`action.yml`). They run inside CI with the build's permissions and read untrusted repository
  content (YAML, properties, Java sources), so for example: code execution or file writes outside the report
  paths caused by a crafted config or source file, path traversal, YAML parsing that can be abused, or the Action
  running a jar whose checksum it did not verify.
- **The runtime starter**, in particular **apply mode** (`springtune.mode=apply`, experimental): `POST
  /actuator/tuning` changing a pool or executor without an authenticated principal, applying a value other than the
  current recommendation, bypassing the budget, the kill switches (`springtune.apply.enabled=false`, `freeze`) or
  the automatic revert, or leaking data through `GET /actuator/tuning`.
- **`springtune-limiter`**: a way for a client to disable the limiter or make it reject all traffic other than by
  the overload it is designed to react to.
- **Release integrity**: artifacts on Maven Central or GitHub Releases that do not match their signatures,
  `SHA256SUMS` or SBOM.

Out of scope:

- Exposing `/actuator/tuning` without securing it yourself in `observe` or `advise` mode: it is read-only, and
  [docs/starter.md](docs/starter.md#apply-mode-experimental) says how to secure it. Apply mode refuses to start
  without Spring Security.
- A rule that misses a risky setting or flags a safe one: that is a
  [false positive](https://github.com/pvgiang/spring-tune/issues/new?template=1-false-positive.yml) or a rule
  request, not a vulnerability.
- Vulnerabilities in dependencies (Spring Boot, Jackson, HikariCP, ...) with no SpringTune-specific impact: report
  them upstream. Dependabot keeps ours current.
- Findings from automated scanners without a demonstrated impact.

## What to expect

SpringTune is maintained by **one person**, in spare time. Honest expectations, not an SLA:

- **Acknowledgement** within 7 days.
- **First assessment** (is it in scope, how severe) within 14 days.
- **Fix**: for a confirmed high or critical issue, a patch release as soon as one can be made and tested, aimed at
  within 30 days; lower severities go into the next planned release. If a fix will take longer, you will be told why.
- **Disclosure**: coordinated. The advisory is published when the fixed version is on Maven Central, or after 90
  days, whichever comes first, unless we agree otherwise. You are credited in the advisory unless you prefer not to be.

There is no bug bounty.
