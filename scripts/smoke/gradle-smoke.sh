#!/usr/bin/env bash
# Post-release smoke test, Gradle side (docs/releasing.md#post-release-smoke).
#
#   scripts/smoke/gradle-smoke.sh VERSION [portal|central|staging] [STAGING_REPO_URL]
#
# Copies scripts/smoke/gradle to a work directory and runs it with a FRESH Gradle user home (no caches, no init
# scripts, no mavenLocal()). The plugin is applied by id, resolved from the Gradle Plugin Portal (portal, default),
# from Maven Central's plugin marker (central) or, for a local rehearsal, from STAGING_REPO_URL only (staging).
# STAGING_REPO_URL (e.g. file:///.../target/staging-repo) is also searched first for the engine, starter and
# limiter.
#
# Proves: the plugin resolves by id; starter, limiter and the engine resolve from the repositories; springtuneCheck
# writes a SARIF report with ST-HIKARI-001 (failOn=NONE, configuration cache on) and fails the build with the
# default failOn=ERROR.
#
# Environment: GRADLE (default: gradle on PATH), SMOKE_WORK (default: a new temp dir), WAIT_MINUTES (default 0):
# poll Central/the Portal until the artifacts are visible, at most this long. The build itself is never retried.
set -euo pipefail

VERSION="${1:?usage: gradle-smoke.sh VERSION [portal|central|staging] [STAGING_REPO_URL]}"
PLUGIN_REPO="${2:-portal}"
STAGING="${3:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
WAIT_MINUTES="${WAIT_MINUTES:-0}"
GRADLE="${GRADLE:-gradle}"
GROUP_PATH="io/github/winkytek/springtune"

echo "SpringTune Gradle smoke: version $VERSION, plugin from $PLUGIN_REPO, staging repository ${STAGING:-none}"
"$GRADLE" --version
mkdir -p "$WORK"
rm -rf "$WORK/project" "$WORK/gradle-home"
cp -R "$HERE/gradle" "$WORK/project"
export GRADLE_USER_HOME="$WORK/gradle-home"

if [ -z "$STAGING" ] && [ "$WAIT_MINUTES" -gt 0 ]; then
  CENTRAL="https://repo.maven.apache.org/maven2/$GROUP_PATH"
  URLS=(
    "$CENTRAL/springtune-model/$VERSION/springtune-model-$VERSION.jar"
    "$CENTRAL/springtune-check-engine/$VERSION/springtune-check-engine-$VERSION.jar"
    "$CENTRAL/springtune-spring-boot-autoconfigure/$VERSION/springtune-spring-boot-autoconfigure-$VERSION.jar"
    "$CENTRAL/springtune-spring-boot-starter/$VERSION/springtune-spring-boot-starter-$VERSION.pom"
    "$CENTRAL/springtune-limiter/$VERSION/springtune-limiter-$VERSION.jar"
  )
  MARKER="io.github.winkytek.springtune.gradle.plugin/$VERSION/io.github.winkytek.springtune.gradle.plugin-$VERSION.pom"
  case "$PLUGIN_REPO" in
    portal) URLS+=("https://plugins.gradle.org/m2/$GROUP_PATH/$MARKER"
                   "https://plugins.gradle.org/m2/$GROUP_PATH/springtune-gradle-plugin/$VERSION/springtune-gradle-plugin-$VERSION.jar") ;;
    central) URLS+=("$CENTRAL/$MARKER" "$CENTRAL/springtune-gradle-plugin/$VERSION/springtune-gradle-plugin-$VERSION.jar") ;;
  esac
  deadline=$(( $(date +%s) + WAIT_MINUTES * 60 ))
  while :; do
    missing=()
    for u in "${URLS[@]}"; do
      curl -fsIL -o /dev/null "$u" || missing+=("$u")
    done
    [ ${#missing[@]} -eq 0 ] && { echo "All artifacts visible."; break; }
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "::error::Not visible after $WAIT_MINUTES min: ${missing[*]}"
      exit 1
    fi
    echo "Waiting for propagation (${#missing[@]} missing, e.g. ${missing[0]}); next check in 60 s"
    sleep 60
  done
fi

ARGS=(-p "$WORK/project" --no-daemon --stacktrace --warning-mode all "-PspringtuneVersion=$VERSION" "-PpluginRepo=$PLUGIN_REPO")
[ -n "$STAGING" ] && ARGS+=("-PstagingRepo=$STAGING")

echo "== 1. runtimeClasspath resolves (starter, limiter and their dependencies)"
"$GRADLE" "${ARGS[@]}" resolveRuntimeClasspath | tee "$WORK/dependencies.txt"
grep -q "resolved: springtune-spring-boot-autoconfigure-" "$WORK/dependencies.txt"
grep -q "resolved: springtune-limiter-" "$WORK/dependencies.txt"
echo "OK"

echo "== 2. springtuneCheck with failOn=NONE and the configuration cache"
"$GRADLE" "${ARGS[@]}" springtuneCheck -Pspringtune.failOn=NONE --configuration-cache
SARIF="$WORK/project/build/springtune/springtune-report.sarif"
grep -q '"ruleId" : "ST-HIKARI-001"' "$SARIF" || { echo "::error::ST-HIKARI-001 missing from $SARIF"; exit 1; }
echo "OK: SARIF report contains ST-HIKARI-001"

echo "== 3. springtuneCheck with the default failOn=ERROR must fail"
set +e
"$GRADLE" "${ARGS[@]}" springtuneCheck > "$WORK/check-error.log" 2>&1
status=$?
set -e
cat "$WORK/check-error.log"
[ "$status" -ne 0 ] || { echo "::error::springtuneCheck passed on a risky configuration"; exit 1; }
grep -q "ST-HIKARI-001" "$WORK/check-error.log"
echo "OK: build failed on ST-HIKARI-001"

echo "Gradle smoke passed for $VERSION (plugin from $PLUGIN_REPO)"
