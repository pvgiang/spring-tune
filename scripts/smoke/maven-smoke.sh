#!/usr/bin/env bash
# Post-release smoke test, Maven side (docs/releasing.md#post-release-smoke).
#
#   scripts/smoke/maven-smoke.sh VERSION [STAGING_REPO_URL]
#
# Copies scripts/smoke/maven to a work directory and builds it with an EMPTY local repository and no user
# settings, so every SpringTune artifact must come from Maven Central (or, for a local rehearsal, from
# STAGING_REPO_URL, e.g. file:///.../target/staging-repo, searched before Central).
#
# Proves: the starter and limiter resolve and auto-configure in a Boot app (one @SpringBootTest), the Maven plugin
# resolves and its check finds ST-HIKARI-001 (report with failOn=NONE, build failure with the default ERROR), and
# springtune-check-cli:VERSION:all downloads and runs.
#
# Environment: SMOKE_WORK (work directory, default: a new temp dir), WAIT_MINUTES (default 0): poll Central until
# every artifact is visible, at most this long, before building. The build itself is never retried.
set -euo pipefail

VERSION="${1:?usage: maven-smoke.sh VERSION [STAGING_REPO_URL]}"
STAGING="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
WAIT_MINUTES="${WAIT_MINUTES:-0}"
CENTRAL="https://repo.maven.apache.org/maven2"
GROUP_PATH="io/github/winkytek/springtune"

echo "SpringTune Maven smoke: version $VERSION, work directory $WORK, staging repository ${STAGING:-none}"
mkdir -p "$WORK"
rm -rf "$WORK/project" "$WORK/m2" "$WORK/cli"
cp -R "$HERE/maven" "$WORK/project"

# Settings: none of the user's mirrors or repositories. With a staging repository, search it first.
if [ -n "$STAGING" ]; then
  cat > "$WORK/settings.xml" <<EOF
<settings>
  <profiles>
    <profile>
      <id>staging</id>
      <repositories>
        <repository><id>staging</id><url>$STAGING</url><snapshots><enabled>true</enabled></snapshots></repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository><id>staging</id><url>$STAGING</url><snapshots><enabled>true</enabled></snapshots></pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>staging</activeProfile></activeProfiles>
</settings>
EOF
else
  echo "<settings/>" > "$WORK/settings.xml"
fi

# Central propagation: wait until every file the build needs is visible, so a failed lookup is never cached in
# the fresh local repository. Only when building against Central.
if [ -z "$STAGING" ] && [ "$WAIT_MINUTES" -gt 0 ]; then
  FILES=(
    "springtune-parent/$VERSION/springtune-parent-$VERSION.pom"
    "springtune-model/$VERSION/springtune-model-$VERSION.jar"
    "springtune-check-engine/$VERSION/springtune-check-engine-$VERSION.jar"
    "springtune-spring-boot-autoconfigure/$VERSION/springtune-spring-boot-autoconfigure-$VERSION.jar"
    "springtune-spring-boot-starter/$VERSION/springtune-spring-boot-starter-$VERSION.pom"
    "springtune-limiter/$VERSION/springtune-limiter-$VERSION.jar"
    "springtune-maven-plugin/$VERSION/springtune-maven-plugin-$VERSION.jar"
    "springtune-check-cli/$VERSION/springtune-check-cli-$VERSION-all.jar"
  )
  deadline=$(( $(date +%s) + WAIT_MINUTES * 60 ))
  while :; do
    missing=()
    for f in "${FILES[@]}"; do
      curl -fsIL -o /dev/null "$CENTRAL/$GROUP_PATH/$f" || missing+=("$f")
    done
    [ ${#missing[@]} -eq 0 ] && { echo "All artifacts visible on Central."; break; }
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "::error::Not on Maven Central after $WAIT_MINUTES min: ${missing[*]}"
      exit 1
    fi
    echo "Waiting for Central (${#missing[@]} missing, e.g. ${missing[0]}); next check in 60 s"
    sleep 60
  done
fi

MVN=(mvn -B -ntp -s "$WORK/settings.xml" "-Dmaven.repo.local=$WORK/m2" "-Dspringtune.version=$VERSION")
cd "$WORK/project"

echo "== 1. verify: test boots the app with starter + limiter; check runs with failOn=NONE"
"${MVN[@]}" verify -Dspringtune.failOn=NONE
grep -q '"ruleId" : "ST-HIKARI-001"' target/springtune/springtune-report.sarif \
  || { echo "::error::ST-HIKARI-001 missing from target/springtune/springtune-report.sarif"; exit 1; }
test -f target/springtune/springtune-report.md
echo "OK: tests passed, SARIF and Markdown reports contain ST-HIKARI-001"

echo "== 2. check with the default failOn=ERROR must fail the build"
set +e
"${MVN[@]}" -o "io.github.winkytek.springtune:springtune-maven-plugin:$VERSION:check" > "$WORK/check-error.log" 2>&1
status=$?
set -e
cat "$WORK/check-error.log"
[ "$status" -ne 0 ] || { echo "::error::check with failOn=ERROR passed on a risky configuration"; exit 1; }
grep -q "ST-HIKARI-001" "$WORK/check-error.log"
grep -q "at or above ERROR" "$WORK/check-error.log"
echo "OK: build failed on ST-HIKARI-001"

echo "== 3. springtune-check-cli:$VERSION:all from the repository"
"${MVN[@]}" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
  "-Dartifact=io.github.winkytek.springtune:springtune-check-cli:$VERSION:jar:all" "-DoutputDirectory=$WORK/cli" \
  -Dmdep.useBaseVersion=true
CLI="$WORK/cli/springtune-check-cli-$VERSION-all.jar"
java -jar "$CLI" --version | tee "$WORK/cli-version.txt"
grep -q "SpringTune $VERSION" "$WORK/cli-version.txt"
set +e
java -jar "$CLI" check "$WORK/project" --fail-on ERROR -o "$WORK/cli/reports" > "$WORK/cli-check.txt" 2>&1
status=$?
set -e
cat "$WORK/cli-check.txt"
[ "$status" -eq 1 ] || { echo "::error::CLI exit code $status, expected 1 (blocking finding)"; exit 1; }
grep -q "ST-HIKARI-001" "$WORK/cli-check.txt"
echo "OK: CLI $VERSION exits 1 on ST-HIKARI-001"

echo "Maven smoke passed for $VERSION"
