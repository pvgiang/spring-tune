#!/usr/bin/env bash
# Executes the README's install snippets in fresh projects, against Maven Central, with empty local repositories.
#
#   scripts/smoke/readme-snippets.sh maven|gradle-portal|gradle-central|cli [README]
#
# The snippets are the fenced blocks after <!-- snippet:NAME --> in README.md (scripts/smoke/readme-snippet.py).
# Each mode builds a project from the post-release smoke fixtures with the README's text in place of the fixture's
# SpringTune lines, then runs the same assertions as the post-release smoke:
#
#   maven           snippet:maven-plugin and snippet:maven-starter replace the fixture's plugin and starter
#                   declarations in scripts/smoke/maven/pom.xml; then scripts/smoke/maven-smoke.sh.
#   gradle-portal   snippet:gradle-plugin replaces the plugins block of scripts/smoke/gradle/build.gradle.kts, and
#                   settings.gradle.kts declares no plugin repository (Gradle's default, the Plugin Portal); then
#                   scripts/smoke/gradle-smoke.sh.
#   gradle-central  the same, with only mavenCentral() in pluginManagement.repositories, the README's fallback.
#   cli             snippet:cli runs as written (download from Central, sha256sum -c, check), on the Maven fixture instead of
#                   path/to/repo; it must verify the jar and exit 1 on ST-HIKARI-001.
#
# The SpringTune version is read from the snippets, which must all name the same one. Environment: SMOKE_WORK
# (default: a new temp dir), GRADLE (default: gradle). Run by the "README snippets" job in .github/workflows/ci.yml.
set -euo pipefail

MODE="${1:?usage: readme-snippets.sh maven|gradle-portal|gradle-central|cli [README]}"
HERE="$(cd "$(dirname "$0")" && pwd)"
README="${2:-$HERE/../../README.md}"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"

snippet() { python3 -I "$HERE/readme-snippet.py" "$README" "$1"; }

# One version across the install snippets, so a half-updated README fails here. Each snippet must name one.
ver() {  # ver SNIPPET SED_EXPRESSION
  local v
  v="$(snippet "$1" | sed -n "$2" | sort -u)"
  [ "$(printf '%s\n' "$v" | grep -c .)" -eq 1 ] \
    || { echo "::error file=README.md::snippet:$1 should name exactly one SpringTune version, found '$(echo $v)'" >&2; exit 1; }
  echo "$v"
}
versions="$(
  { ver maven-plugin 's:.*<version>\(.*\)</version>.*:\1:p'
    ver maven-starter 's:.*<version>\(.*\)</version>.*:\1:p'
    ver gradle-plugin 's:.*id("io.github.winkytek.springtune") version "\([^"]*\)".*:\1:p'
    ver cli 's:.*springtune-\(check-\)\{0,1\}cli-\([0-9][0-9.]*[0-9]\)\(-all\)\{0,1\}\.jar.*:\2:p'
  } | sort -u
)"
[ -n "$versions" ] && [ "$(printf '%s\n' "$versions" | grep -c .)" -eq 1 ] \
  || { echo "::error file=README.md::install snippets name different versions: $(echo $versions)"; exit 1; }
VERSION="$versions"
echo "README install snippets: SpringTune $VERSION, mode $MODE, work directory $WORK"

FIXTURE="$WORK/fixture"
rm -rf "$FIXTURE"

case "$MODE" in
  maven)
    cp -R "$HERE/maven" "$FIXTURE"
    snippet maven-plugin > "$WORK/maven-plugin.xml"
    snippet maven-starter > "$WORK/maven-starter.xml"
    python3 -I - "$FIXTURE/pom.xml" "$WORK/maven-plugin.xml" "$WORK/maven-starter.xml" <<'EOF'
import re, sys
pom_path, plugin_path, starter_path = sys.argv[1:4]
pom = open(pom_path, encoding="utf-8").read()
def swap(pom, artifact, kind, replacement):
    pattern = re.compile(r"<%s>\s*<groupId>io\.github\.winkytek\.springtune</groupId>\s*<artifactId>%s</artifactId>.*?</%s>"
                         % (kind, re.escape(artifact), kind), re.S)
    pom, n = pattern.subn(lambda m: replacement.strip(), pom)
    if n != 1:
        sys.exit(f"expected one {artifact} {kind} in {pom_path}, found {n}")
    return pom
pom = swap(pom, "springtune-maven-plugin", "plugin", open(plugin_path, encoding="utf-8").read())
pom = swap(pom, "springtune-spring-boot-starter", "dependency", open(starter_path, encoding="utf-8").read())
open(pom_path, "w", encoding="utf-8").write(pom)
EOF
    echo "== pom.xml with the README snippets"
    cat "$FIXTURE/pom.xml"
    SMOKE_FIXTURE="$FIXTURE" SMOKE_WORK="$WORK/run" "$HERE/maven-smoke.sh" "$VERSION"
    ;;

  gradle-portal | gradle-central)
    cp -R "$HERE/gradle" "$FIXTURE"
    snippet gradle-plugin > "$WORK/gradle-plugin.kts"
    python3 -I - "$FIXTURE/build.gradle.kts" "$WORK/gradle-plugin.kts" <<'EOF'
import re, sys
build_path, snippet_path = sys.argv[1:3]
build = open(build_path, encoding="utf-8").read()
snippet = open(snippet_path, encoding="utf-8").read()
# The README shows the SpringTune line of a Java project's plugins block; the fixture needs `java` too.
snippet, n = re.subn(r"^plugins \{\n", "plugins {\n    java\n", snippet, count=1, flags=re.M)
if n != 1:
    sys.exit("snippet:gradle-plugin has no 'plugins {' block")
build, n = re.subn(r"^plugins \{.*?^\}\n", lambda m: snippet.rstrip("\n") + "\n", build, count=1, flags=re.M | re.S)
if n != 1:
    sys.exit(f"no plugins block in {build_path}")
open(build_path, "w", encoding="utf-8").write(build)
EOF
    if [ "$MODE" = gradle-portal ]; then
      printf '%s\n' '// No pluginManagement: Gradle resolves plugins from its default repository, the Plugin Portal.' \
        'rootProject.name = "springtune-readme-gradle"' > "$FIXTURE/settings.gradle.kts"
      plugin_repo=portal
    else
      printf '%s\n' '// The README fallback for builds without gradlePluginPortal().' \
        'pluginManagement { repositories { mavenCentral() } }' \
        'rootProject.name = "springtune-readme-gradle"' > "$FIXTURE/settings.gradle.kts"
      plugin_repo=central
    fi
    echo "== settings.gradle.kts and build.gradle.kts with the README snippet"
    cat "$FIXTURE/settings.gradle.kts" "$FIXTURE/build.gradle.kts"
    SMOKE_FIXTURE="$FIXTURE" SMOKE_WORK="$WORK/run" "$HERE/gradle-smoke.sh" "$VERSION" "$plugin_repo"
    ;;

  cli)
    cp -R "$HERE/maven" "$FIXTURE"
    mkdir -p "$WORK/cli"
    snippet cli | sed "s#path/to/repo#$FIXTURE#" > "$WORK/cli/snippet.sh"
    echo "== snippet:cli as run"
    cat "$WORK/cli/snippet.sh"
    set +e
    (cd "$WORK/cli" && bash -e snippet.sh) > "$WORK/cli/out.txt" 2>&1
    status=$?
    set -e
    cat "$WORK/cli/out.txt"
    grep -Eq "springtune-(check-)?cli-$VERSION(-all)?\.jar: OK" "$WORK/cli/out.txt" \
      || { echo "::error::snippet:cli did not verify the jar's SHA-256"; exit 1; }
    grep -q "SpringTune $VERSION" "$WORK/cli/out.txt" || { echo "::error::CLI did not report version $VERSION"; exit 1; }
    grep -q "ST-HIKARI-001" "$WORK/cli/out.txt" || { echo "::error::CLI did not report ST-HIKARI-001"; exit 1; }
    [ "$status" -eq 1 ] || { echo "::error::snippet:cli exit code $status, expected 1 (blocking finding)"; exit 1; }
    echo "OK: snippet:cli downloads, verifies and runs CLI $VERSION (exit 1 on ST-HIKARI-001)"
    ;;

  *)
    echo "unknown mode $MODE" >&2
    exit 2
    ;;
esac
echo "README snippets ($MODE) passed for $VERSION"
