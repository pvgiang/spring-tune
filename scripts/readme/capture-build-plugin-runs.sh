#!/usr/bin/env bash
# Produces the Maven and Gradle plugin outputs shown in README.md: the README's install snippets added to copies of
# examples/broken-config-ci, then `mvn -B verify` and `gradle check`.
#
#   scripts/readme/capture-build-plugin-runs.sh VERSION WORK_DIR
#
# VERSION must resolve: from Maven Central, or from ~/.m2 after building that release locally (git worktree at its
# tag, `versions:set -DnewVersion=VERSION`, `mvn install -DskipTests -Dinvoker.skip`). Needs mvn and gradle.
set -euo pipefail
V="$1"; WORK="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
rm -rf "$WORK"; mkdir -p "$WORK"

# Maven: the demo's pom plus snippet:maven-plugin inside <build><plugins>.
cp -r "$ROOT/examples/broken-config-ci" "$WORK/maven"
python3 -I - "$WORK/maven/pom.xml" "$V" <<'PY'
import sys
path, v = sys.argv[1:3]
s = open(path).read().replace("<springtune.version>0.4.1</springtune.version>", f"<springtune.version>{v}</springtune.version>")
plugin = f"""<plugin>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-maven-plugin</artifactId>
  <version>{v}</version>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
"""
open(path, "w").write(s.replace("</plugins>", plugin + "</plugins>", 1))
PY
rc=0; (cd "$WORK/maven" && mvn -B -ntp verify) > "$WORK/maven.log" 2>&1 || rc=$?
echo "=== mvn verify exit code: $rc"
grep -v '^Picked up JAVA_TOOL_OPTIONS' "$WORK/maven.log" | sed -n '/--- springtune:/,/BUILD FAILURE\|BUILD SUCCESS/p'
grep -m1 '^\[ERROR\] Failed to execute goal' "$WORK/maven.log" || true

# Gradle: the demo's sources with snippet:gradle-plugin, java and the dependencies the demo's pom declares.
mkdir -p "$WORK/gradle"
cp -r "$ROOT/examples/broken-config-ci/src" "$ROOT/examples/broken-config-ci/springtune.yml" "$WORK/gradle/"
cat > "$WORK/gradle/settings.gradle.kts" <<KTS
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }
rootProject.name = "orders-service"
KTS
cat > "$WORK/gradle/build.gradle.kts" <<KTS
plugins {
    java
    id("io.github.winkytek.springtune") version "$V"
}
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.github.winkytek.springtune:springtune-spring-boot-starter:$V")
    runtimeOnly("com.h2database:h2")
}
KTS
rc=0; (cd "$WORK/gradle" && gradle --no-daemon --console=plain check) > "$WORK/gradle.log" 2>&1 || rc=$?
echo "=== gradle check exit code: $rc"
grep -v '^Picked up JAVA_TOOL_OPTIONS' "$WORK/gradle.log" | sed -n '/> Task :springtuneCheck/,/BUILD FAILED\|BUILD SUCCESSFUL/p'
