# Releasing

A release is one tag. [`.github/workflows/release.yml`](../.github/workflows/release.yml) builds and tests it,
publishes the GitHub Release (CLI jar, Maven repository zip, [SBOM](#sboms), checksums), and, **when the repository secrets below
exist**, deploys the same artifacts to Maven Central. Without the secrets the Central job is skipped and the
release is GitHub-only, exactly as for 0.1.0. The Gradle Plugin Portal is a separate, manual workflow.

```
tag vX.Y.Z ──► release job ──► GitHub Release
                    │
                    └─ secrets set? ──► maven-central job ──► central.sonatype.com (UPLOAD: you press Publish)
                                                                     │ live on Central (10-30 min)
                                                                     ▼
                                     Actions › Gradle Plugin Portal › Run workflow ──► plugins.gradle.org
                                                                     │
                                                                     ▼
                                     Actions › Post-release smoke › Run workflow (clean Maven + Gradle builds)
```

## Every release

1. Write `docs/releases/vX.Y.Z.md` (the workflow fails without it) and merge it.
2. Tag: `git tag -a vX.Y.Z -m "SpringTune X.Y.Z" && git push origin vX.Y.Z`, or *Actions → Release → Run workflow*.
3. If the *Publish to Maven Central* job ran: open
   [central.sonatype.com → Publishing → Deployments](https://central.sonatype.com/publishing/deployments), check the
   deployment is *Validated* and contains every module, and press **Publish**. (Skip this once the repository
   variable `CENTRAL_STAGE` is `FULL`.)
4. When `https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-engine/X.Y.Z/` exists, run
   *Actions → Gradle Plugin Portal → Run workflow* with `X.Y.Z`. It refuses to run before the engine is on Central,
   because the plugin resolves the engine from the user's repositories at check time.

5. Run *Actions → Post-release smoke → Run workflow* with `X.Y.Z` ([below](#post-release-smoke)). The release is
   done when it is green.

Central releases cannot be deleted or overwritten. A broken release is fixed with a new version.

## Releasing 0.4.0

0.4.0 is the **first release on Maven Central** (0.3.0 was GitHub-only: the Central job was skipped because the
secrets were missing). Before starting, the [one-time setup](#one-time-setup-a-human-has-to-do-these) must be done,
all five Central secrets and both Plugin Portal secrets must be set, and `docs/releases/v0.4.0.md` must have no
`TODO(verify)` left.

1. **Release.** On `main` with the version bump merged: *Actions → Release → Run workflow*, version `0.4.0` (or push
   the tag `v0.4.0`). Check that the run log does **not** show the notice "Maven Central secrets not set": if it
   does, the release is GitHub-only again; stop and fix the secrets before releasing 0.4.1.
2. **Publish on Central.** The *Publish to Maven Central* job uploads with stage `UPLOAD`. On
   [central.sonatype.com → Publishing → Deployments](https://central.sonatype.com/publishing/deployments), check the
   deployment is *Validated* and lists every published module (parent, model, check-engine, check-cli, maven-plugin,
   gradle-plugin, gradle-plugin marker, spring-boot-autoconfigure, spring-boot-starter, limiter), then press
   **Publish**. After this first manual check, set the repository variable `CENTRAL_STAGE=FULL`.
3. **Plugin Portal.** When
   `https://repo.maven.apache.org/maven2/io/github/winkytek/springtune/springtune-check-engine/0.4.0/` exists (10–30
   min after Publish), run *Actions → Gradle Plugin Portal → Run workflow* with `0.4.0`. The first version of the
   plugin is reviewed by hand by the Portal team, which can take days; the `central` half of the Gradle smoke does not
   depend on it.
4. **Smoke.** Run *Actions → Post-release smoke → Run workflow* with `0.4.0`. If the Portal review is still pending,
   the `portal` jobs wait 30 minutes and fail; rerun them once the plugin is approved.

## Post-release smoke

[`.github/workflows/post-release-smoke.yml`](../.github/workflows/post-release-smoke.yml) (`workflow_dispatch` with
the version, also `workflow_call`). Run it after every release, once the Plugin Portal workflow has run. It does not
build SpringTune: it builds two user projects that only know the version number.

| Job | What it does | What it proves |
|---|---|---|
| `maven-smoke` | [`scripts/smoke/maven-smoke.sh`](../scripts/smoke/maven-smoke.sh) on Java 17, empty `-Dmaven.repo.local` under `$RUNNER_TEMP`, empty settings. The fixture [`scripts/smoke/maven`](../scripts/smoke/maven) depends on `springtune-spring-boot-starter` and `springtune-limiter`, binds `springtune-maven-plugin:check` and has a risky `connection-timeout: 0` | Starter, limiter and plugin resolve from Central with their whole dependency trees (POM metadata, parent, BOM imports); a `@SpringBootTest` boots the app, `/hello` passes through the limiter and `/actuator/tuning` lists the Hikari pool; the check writes SARIF and Markdown with ST-HIKARI-001 and, with the default `failOn=ERROR`, fails the build; `springtune-check-cli:<version>:all` downloads, prints the version and exits 1 on the same finding |
| `gradle-smoke` | [`scripts/smoke/gradle-smoke.sh`](../scripts/smoke/gradle-smoke.sh), matrix Gradle 8.14.3 and 9.8.2 × plugin from `portal` (`gradlePluginPortal()` only) or `central` (`mavenCentral()` only), fresh `GRADLE_USER_HOME`, no `mavenLocal()`. The fixture [`scripts/smoke/gradle`](../scripts/smoke/gradle) applies `id("io.github.winkytek.springtune")` | The plugin resolves by id from each source; the engine, starter and limiter resolve from Central; `springtuneCheck` runs with the configuration cache, reports ST-HIKARI-001 and fails the build on it |

Before building, each job polls Central (and the Portal, for `portal`) every minute, up to 30 minutes, until every
file it needs is visible; a lookup that fails during propagation is never cached in the fresh repository. The builds
themselves are never retried, so a red job is a real problem with the release, not propagation lag.

What it does not prove: Spring Boot 3.5 users (the fixture uses Boot 4.1.2), Java 21+ consumers, signature
verification by consumers, or the GitHub Action (`action.yml`).

### Rehearse it locally against a staged repository

The fixtures take the version and an optional staging repository, searched before Central:

```bash
STAGE=$PWD/target/smoke-stage
VERSION=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)    # a SNAPSHOT is fine
mvn -B -ntp deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$STAGE
scripts/smoke/maven-smoke.sh "$VERSION" "file://$STAGE"
GRADLE=/opt/gradle/bin/gradle scripts/smoke/gradle-smoke.sh "$VERSION" staging "file://$STAGE"
```

`staging` takes the plugin from the staged repository only; `portal` and `central` need a released version. Plain
`deploy` (without `-Prelease`) stages only SpringTune's own artifacts; everything else still comes from Central.
Each script works in a new temp directory (or `SMOKE_WORK`) and leaves the repository untouched.

If Central answers `429 Too Many Requests` (seen from rate-limited sandboxes, rarely from GitHub runners), the build
fails with that cause in the log; rerun the job.

## What the `release` Maven profile does

Normal builds never activate it; `mvn verify` is unchanged.

| Step | Command (the workflow runs these) | Result |
|---|---|---|
| Stage | `mvn -B -ntp -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo` | Every published module plus `-sources.jar`, `-javadoc.jar` and `-cyclonedx.json` ([SBOMs](#sboms)) in `target/staging-repo`. The starter has no code, so it gets placeholder jars (Central requires both for every jar). Samples and the Gradle plugin's test module are not deployed. |
| Deploy | `mvn -B -ntp -Prelease -N jreleaser:deploy` | [JReleaser](https://jreleaser.org) signs every file with the in-memory GPG key, adds checksums, checks Central's rules (POM metadata, sources, javadoc, signatures) and uploads one bundle to the Central Portal API. |

POM metadata Central requires (name, description, url, licenses, developers, scm) is in the root POM. The modules
with flattened POMs (model, engine, Gradle plugin and marker) keep it (`flattenMode` `ossrh`); the others inherit it
from `springtune-parent`, which is published too, and repeat `<url>` because Central's POM check wants it in
every POM. **A new published module** (for example `springtune-limiter`) needs a `<name>`, `<description>` and
`<url>`, or the deploy step fails the POM check and names the file.

### SBOMs

The `release` profile runs [cyclonedx-maven-plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin) 2.9.3
(CycloneDX 1.6, JSON) in the `package` phase. Normal builds never run it.

| File | Where it goes | Contents |
|---|---|---|
| `<artifactId>-<version>-cyclonedx.json` (classifier `cyclonedx`, type `json`), one per published module | Attached to the module, so it is staged, signed and checksummed by JReleaser and deployed to Central next to the jar | That module's compile and runtime dependencies (no test, provided or system scope) |
| `target/springtune-<version>-sbom.cyclonedx.json`, one for the release | Not attached; `release.yml` copies it to the GitHub Release (and into `SHA256SUMS`) | All published modules and their dependencies, as one graph |

Modules with `maven.deploy.skip` (samples, load tests, the Gradle plugin's test module, benchmarks) get no SBOM and
are left out of the aggregate (`skipNotDeployed`). The Gradle plugin's SBOM lists no third-party components: the
engine is `provided` and resolved at check time from the user's repositories. The Plugin Portal build
(`springtune-gradle-plugin/portal`) publishes no SBOM.

Check locally (no secrets needed):

```bash
mvn -B -ntp -Prelease -DskipTests -Dinvoker.skip package | grep 'attaching as .*-cyclonedx.json'
jq '[.components[] | .group + ":" + .name + ":" + .version]' target/springtune-*-sbom.cyclonedx.json
```

The dry run below signs the `-cyclonedx.json` files with everything else; the Central bundle then contains them.

### Try it locally without secrets

```bash
# 1. stage, with a release version (Central does not take SNAPSHOTs)
mvn -B -ntp -q org.codehaus.mojo:versions-maven-plugin:2.21.0:set -DnewVersion=9.9.9 -DgenerateBackupPoms=false -DprocessAllModules=true
mvn -B -ntp -q -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo

# 2. throwaway key and fake credentials, dry run: everything except the upload
export GNUPGHOME=$(mktemp -d)    # keep the path short: gpg-agent's socket path must stay under 108 characters
gpg --batch --pinentry-mode loopback --passphrase test --quick-gen-key "Test <test@example.invalid>" rsa3072 sign 1d
export JRELEASER_GPG_PUBLIC_KEY="$(gpg --armor --export)"
export JRELEASER_GPG_SECRET_KEY="$(gpg --batch --pinentry-mode loopback --passphrase test --armor --export-secret-keys)"
export JRELEASER_GPG_PASSPHRASE=test JRELEASER_MAVENCENTRAL_USERNAME=x JRELEASER_MAVENCENTRAL_PASSWORD=x
mvn -B -ntp -Prelease -N jreleaser:deploy -Djreleaser.dry.run=true   # expect: JReleaser succeeded

# 3. back to the development version you started from
mvn -B -ntp -q org.codehaus.mojo:versions-maven-plugin:2.21.0:set -DnewVersion=<previous>-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules=true
```

The dry run warns that the throwaway key "was NOT found as published"; with the real key it must not.

## One-time setup (a human has to do these)

None of this can be done or verified from CI or by an agent: it needs accounts, identity checks and secrets.

### 1. Sonatype Central namespace `io.github.winkytek`

All artifacts use groupId `io.github.winkytek.springtune`, so the Central Portal account must own the namespace
`io.github.winkytek`.

- The GitHub account **`winkytek`** owns the namespace (it is a collaborator on `pvgiang/spring-tune`). Sign in at
  [central.sonatype.com](https://central.sonatype.com) **with the GitHub account `winkytek`**; Central then
  verifies `io.github.winkytek` automatically. With any other login, *Namespaces → Add namespace →
  io.github.winkytek* asks you to create a temporary public repository under `github.com/winkytek` named with a
  verification key; do that while signed in to GitHub as `winkytek`.
- Generate the user token (step 2) on that same Central account.
- In 0.1.0 (and the unreleased 0.2.0) the groupId was `io.github.winky.springtune` (GitHub release zip only, never on Central). From
  0.3.0 it is `io.github.winkytek.springtune`, Java packages are `io.github.winkytek.springtune.*` and the Gradle
  plugin id is `io.github.winkytek.springtune`.

### 2. Central user token

Central Portal → *Account* → **Generate User Token**. Store the two values as repository secrets
(*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | token username (not your login) |
| `CENTRAL_PASSWORD` | token password |

### 3. GPG signing key

Central requires a signature on every file, checked against public key servers.

```bash
gpg --quick-gen-key "SpringTune Releases <you@example.com>" ed25519 sign 2y      # asks for a passphrase
KEY=$(gpg --list-keys --with-colons "SpringTune Releases" | awk -F: '/^fpr/ {print $10; exit}')
gpg --keyserver keyserver.ubuntu.com --send-keys "$KEY"
gpg --keyserver keys.openpgp.org --send-keys "$KEY"        # then confirm the e-mail keys.openpgp.org sends
gpg --armor --export "$KEY"             > public.asc
gpg --armor --export-secret-keys "$KEY" > secret.asc       # store as secrets, then delete both files
```

| Secret | Value |
|---|---|
| `GPG_PUBLIC_KEY` | content of `public.asc` |
| `GPG_SECRET_KEY` | content of `secret.asc` |
| `GPG_PASSPHRASE` | the passphrase |

Keep an offline backup of the key and a revocation certificate; losing the key means new releases are signed by
a different key, which users with signature verification will notice.

The workflow checks that **all five** secrets are set; if one is missing, the Central job is skipped with a notice.

### 4. Optional: publish without the manual step

After one release has been checked by hand on the Central Portal, set the repository **variable** (not secret)
`CENTRAL_STAGE=FULL`. JReleaser then publishes as soon as Central has validated the bundle.

### 5. Issue labels

The issue forms only apply labels that exist. Create them with the commands in
[adoption/gates.md](adoption/gates.md#triage-labels).

### 6. Gradle Plugin Portal account

- Sign in at [plugins.gradle.org](https://plugins.gradle.org) (GitHub login), open *API Keys* and store them as
  secrets `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.
- Sign in **as `winkytek`**. The first version of a plugin is **reviewed by hand** by the Portal team. Plugin IDs
  under `io.github.<user>` are expected to belong to that GitHub user; `winkytek` publishing
  `io.github.winkytek.springtune` matches. If the reviewer asks why the website and VCS URL point at
  `github.com/pvgiang/spring-tune`, answer that `winkytek` is a collaborator on that repository (or move the
  repository to `github.com/winkytek/spring-tune` to remove the question).

## Gradle Plugin Portal

The Gradle plugin is built by Maven, but the Portal accepts uploads only through the `com.gradle.plugin-publish`
Gradle plugin. The options, honestly:

| Option | What users write | Status |
|---|---|---|
| **A. Maven Central only.** The plugin jar and its marker (`io.github.winkytek.springtune:io.github.winkytek.springtune.gradle.plugin`) are already in the Central bundle. | `pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }` in `settings.gradle(.kts)`, then `id("io.github.winkytek.springtune")` | Works as soon as Central works. Same mechanism the plugin's functional tests use today with a staged repository; not yet tried against Central itself. |
| **B. Portal, from a small Gradle build.** [`springtune-gradle-plugin/portal/`](../springtune-gradle-plugin/portal/build.gradle.kts) compiles the same sources with Gradle and publishes with `com.gradle.plugin-publish`. Run by [`gradle-plugin-portal.yml`](../.github/workflows/gradle-plugin-portal.yml). | Nothing: `plugins { id("io.github.winkytek.springtune") version "X.Y.Z" }` | Built locally with Gradle 8.14.3 (`build publishToMavenLocal`: jar, sources, javadoc, marker, no runtime dependencies). `publishPlugins` has not been run: it needs the account. |
| C. Rewrite the module as a Gradle build. | as B | Not worth it: the Maven reactor builds and tests everything else, including the plugin's functional tests. |

With A and B both in place, users of the Portal get the Gradle-built jar and users of Central the Maven-built
one: same sources and coordinates, different bytes. That only matters to builds that pin artifact checksums
(Gradle dependency verification) and switch repositories.

Local check of option B:

```bash
mvn -B -ntp -q -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo
gradle -p springtune-gradle-plugin/portal build -PspringtuneVersion=<staged version> -PstagingRepo=$PWD/target/staging-repo
```
