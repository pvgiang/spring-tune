#!/usr/bin/env bash
# Produces the check outputs shown in README.md ("What it looks like"), from copies of examples/broken-config-ci.
#
#   scripts/readme/capture-check-runs.sh CLI_JAR WORK_DIR
#
# CLI_JAR is a released CLI (README uses springtune-cli-0.4.1.jar from the v0.4.1 GitHub release, checked against
# its SHA256SUMS). Each case prints its command, its output and its exit code. Nothing here changes the repository.
set -euo pipefail
JAR="$1"; WORK="$2"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
rm -rf "$WORK"; mkdir -p "$WORK"

run() {  # run CASE DIR ARGS...
  local name="$1" dir="$2"; shift 2
  echo "=== $name: springtune $*"
  local rc=0
  (cd "$dir" && java -jar "$JAR" "$@") > "$WORK/out.txt" 2>&1 || rc=$?
  grep -v '^Picked up JAVA_TOOL_OPTIONS' "$WORK/out.txt" || true
  echo "=== exit code: $rc"
  echo
}

# 1. The demo as shipped: four warnings fail the build (springtune.yml sets failOn: WARNING).
cp -r "$ROOT/examples/broken-config-ci" "$WORK/broken"
run "1 broken" "$WORK/broken" check .

# 2. The four fixes from examples/broken-config-ci/README.md applied.
cp -r "$ROOT/examples/broken-config-ci" "$WORK/fixed"
yml="$WORK/fixed/src/main/resources/application.yml"
sed -i 's/connection-timeout: 30000 .*/connection-timeout: 3000/' "$yml"
sed -i 's/^      # SpringTune: ST-HIKARI-002.*/      leak-detection-threshold: 60000/' "$yml"
sed -i 's/max: 400 .*/max: 100/' "$yml"
cat > "$WORK/fixed/src/main/java/com/example/orders/InventoryClient.java" <<'JAVA'
package com.example.orders;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
class InventoryClient {

    private final RestTemplate rest = new RestTemplate(timeouts());

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return factory;
    }

    String stock(String sku) {
        return rest.getForObject("http://inventory:8080/stock/{sku}", String.class, sku);
    }
}
JAVA
run "2 fixed" "$WORK/fixed" check .

# 3. Accepting a finding instead of fixing it: a suppression with a reason.
cp -r "$ROOT/examples/broken-config-ci" "$WORK/suppressed"
cat >> "$WORK/suppressed/springtune.yml" <<'YML'
suppress:
  - rule: ST-TOMCAT-001
    reason: "Most endpoints never touch the database; reviewed 2026-11"
YML
run "3 suppressed" "$WORK/suppressed" check .

# 4. Config that is not in the repository (Spring Cloud Config Server): not evaluated, and why.
mkdir -p "$WORK/config-server/src/main/resources"
cp "$ROOT/examples/broken-config-ci/pom.xml" "$WORK/config-server/"
cat > "$WORK/config-server/src/main/resources/application.yml" <<'YML'
spring:
  application:
    name: payments-service
  config:
    import: "configserver:http://config:8888"
YML
run "4 not evaluated" "$WORK/config-server" check .

# 5. The connection budget, once springtune.yml declares the database limit and the replica count.
cp -r "$ROOT/examples/broken-config-ci" "$WORK/budget"
sed -i 's/maximum-pool-size: 10/maximum-pool-size: 20/' "$WORK/budget/src/main/resources/application.yml"
cat >> "$WORK/budget/springtune.yml" <<'YML'
databases:
  - name: orders-db
    maxConnections: 100
services:
  - path: .
    maxReplicas: 6
YML
run "5 budget" "$WORK/budget" check . --fail-on ERROR
