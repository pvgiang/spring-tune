# Releasing

A release is one tag. [`.github/workflows/release.yml`](../.github/workflows/release.yml) builds and tests it,
publishes the GitHub Release (CLI jar, Maven repository zip, checksums), and, **when the repository secrets below
exist**, deploys the same artifacts to Maven Central. Without the secrets the Central job is skipped and the
release is GitHub-only, exactly as for 0.1.0 and 0.2.0. The Gradle Plugin Portal is a separate, manual workflow.

```
tag vX.Y.Z ──► release job ──► GitHub Release
                    │
                    └─ secrets set? ──► maven-central job ──► central.sonatype.com (UPLOAD: you press Publish)
                                                                     │ live on Central (10-30 min)
                                                                     ▼
                                     Actions › Gradle Plugin Portal › Run workflow ──► plugins.gradle.org
```

## Every release

1. Write `docs/releases/vX.Y.Z.md` (the workflow fails without it) and merge it.
2. Tag: `git tag -a vX.Y.Z -m "SpringTune X.Y.Z" && git push origin vX.Y.Z`, or *Actions → Release → Run workflow*.
3. If the *Publish to Maven Central* job ran: open
   [central.sonatype.com → Publishing → Deployments](https://central.sonatype.com/publishing/deployments), check the
   deployment is *Validated* and contains every module, and press **Publish**. (Skip this once the repository
   variable `CENTRAL_STAGE` is `FULL`.)
4. When `https://repo.maven.apache.org/maven2/io/github/winky/springtune/springtune-check-engine/X.Y.Z/` exists, run
   *Actions → Gradle Plugin Portal → Run workflow* with `X.Y.Z`. It refuses to run before the engine is on Central,
   because the plugin resolves the engine from the user's repositories at check time.

Central releases cannot be deleted or overwritten. A broken release is fixed with a new version.

## What the `release` Maven profile does

Normal builds never activate it; `mvn verify` is unchanged.

| Step | Command (the workflow runs these) | Result |
|---|---|---|
| Stage | `mvn -B -ntp -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo` | Every published module plus `-sources.jar` and `-javadoc.jar` in `target/staging-repo`. The starter has no code, so it gets placeholder jars (Central requires both for every jar). Samples and the Gradle plugin's test module are not deployed. |
| Deploy | `mvn -B -ntp -Prelease -N jreleaser:deploy` | [JReleaser](https://jreleaser.org) signs every file with the in-memory GPG key, adds checksums, checks Central's rules (POM metadata, sources, javadoc, signatures) and uploads one bundle to the Central Portal API. |

POM metadata Central requires (name, description, url, licenses, developers, scm) is in the root POM. The modules
with flattened POMs (model, engine, Gradle plugin and marker) keep it (`flattenMode` `ossrh`); the others inherit it
from `springtune-parent`, which is published too, and repeat `<url>` because Central's POM check wants it in
every POM. **A new published module** (for example `springtune-limiter`) needs a `<name>`, `<description>` and
`<url>`, or the deploy step fails the POM check and names the file.

### Try it locally without secrets

```bash
# 1. stage, with a release version (Central does not take SNAPSHOTs)
mvn -B -ntp -q org.codehaus.mojo:versions-maven-plugin:2.21.0:set -DnewVersion=9.9.9 -DgenerateBackupPoms=false -DprocessAllModules=true
mvn -B -ntp -q -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo

# 2. throwaway key and fake credentials, dry run: everything except the upload
export GNUPGHOME=$(mktemp -d)
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

### 1. Sonatype Central namespace `io.github.winky`

All artifacts use groupId `io.github.winky.springtune`, so the Central Portal account must own the namespace
`io.github.winky`.

- Sign in at [central.sonatype.com](https://central.sonatype.com) **with the GitHub account `winky`**. Central then
  verifies `io.github.winky` automatically. With any other login, *Namespaces → Add namespace → io.github.winky*
  asks you to create a temporary public repository under `github.com/winky` named with a verification key.
- **Check this first.** The repository lives at `github.com/pvgiang/spring-tune`. If you do not control the GitHub
  account `winky`, you cannot verify `io.github.winky`, and the choice is: get that account, use a domain you own
  (`com.example.springtune`, verified with a DNS TXT record), or move the groupId to `io.github.pvgiang`. Moving
  it is cheap now (nothing is on Central yet) and expensive after the first Central release.

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
- The first version of a plugin is **reviewed by hand** by the Portal team. Plugin IDs under `io.github.<user>`
  are expected to belong to that GitHub user, so the same `winky` question as in step 1 applies: the plugin's
  website and VCS URL point at `github.com/pvgiang`.

## Gradle Plugin Portal

The Gradle plugin is built by Maven, but the Portal accepts uploads only through the `com.gradle.plugin-publish`
Gradle plugin. The options, honestly:

| Option | What users write | Status |
|---|---|---|
| **A. Maven Central only.** The plugin jar and its marker (`io.github.winky.springtune:io.github.winky.springtune.gradle.plugin`) are already in the Central bundle. | `pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }` in `settings.gradle(.kts)`, then `id("io.github.winky.springtune")` | Works as soon as Central works. Same mechanism the plugin's functional tests use today with a staged repository; not yet tried against Central itself. |
| **B. Portal, from a small Gradle build.** [`springtune-gradle-plugin/portal/`](../springtune-gradle-plugin/portal/build.gradle.kts) compiles the same sources with Gradle and publishes with `com.gradle.plugin-publish`. Run by [`gradle-plugin-portal.yml`](../.github/workflows/gradle-plugin-portal.yml). | Nothing: `plugins { id("io.github.winky.springtune") version "X.Y.Z" }` | Built locally with Gradle 8.14.3 (`build publishToMavenLocal`: jar, sources, javadoc, marker, no runtime dependencies). `publishPlugins` has not been run: it needs the account. |
| C. Rewrite the module as a Gradle build. | as B | Not worth it: the Maven reactor builds and tests everything else, including the plugin's functional tests. |

With A and B both in place, users of the Portal get the Gradle-built jar and users of Central the Maven-built
one: same sources and coordinates, different bytes. That only matters to builds that pin artifact checksums
(Gradle dependency verification) and switch repositories.

Local check of option B:

```bash
mvn -B -ntp -q -Prelease deploy -DskipTests -Dinvoker.skip -DaltDeploymentRepository=staging::file:$PWD/target/staging-repo
gradle -p springtune-gradle-plugin/portal build -PspringtuneVersion=<staged version> -PstagingRepo=$PWD/target/staging-repo
```
