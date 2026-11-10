#!/usr/bin/env bash
# Produces the runtime outputs shown in README.md ("What it looks like"): the sample app
# (springtune-samples/broken-config) under three loads, read through GET /actuator/tuning and /actuator/tuning/report.
#
#   mvn -q install -DskipTests -Dinvoker.skip -pl springtune-samples/broken-config -am
#   scripts/readme/capture-runtime-runs.sh OUT_DIR
#
# The saturation window is shortened from 5 minutes to 1 minute (springtune.hikari.window=1m) so the run takes
# minutes, not a quarter of an hour; the rules and thresholds are otherwise the defaults. Needs curl and jq.
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$(ls "$ROOT"/springtune-samples/broken-config/target/springtune-sample-broken-config-*.jar | head -1)"
PORT="${PORT:-18081}"; BASE="http://localhost:$PORT"

java -jar "$JAR" --server.port="$PORT" --springtune.hikari.window=1m > "$OUT/app.log" 2>&1 &
APP=$!
trap 'kill $LOAD $APP 2>/dev/null || true' EXIT
LOAD=""
until curl -fs "$BASE/actuator/health" >/dev/null 2>&1; do sleep 1; done

snap() { curl -fs "$BASE/actuator/tuning" > "$OUT/$1.json"; }

load() {  # load CLIENTS HOLD_MS: CLIENTS concurrent callers of /orders?holdMs=HOLD_MS, until killed
  local clients="$1" hold="$2"
  for _ in $(seq "$clients"); do
    while true; do curl -fs -o /dev/null "$BASE/orders?holdMs=$hold" || true; done &
  done
  wait
}

# Case A: just started, no traffic.
sleep 12; snap a-started

# Case B: 60 callers holding a connection 50 ms each, on a pool of 20: short holds, threads waiting.
load 60 50 & LOAD=$!
sleep 80; snap b-short-holds
curl -fs "$BASE/actuator/tuning/report" > "$OUT/b-report.json"
kill $LOAD; pkill -P $LOAD 2>/dev/null || true; pkill -f "holdMs=50" 2>/dev/null || true
sleep 70

# Case C: 60 callers holding a connection 2 s each (a slow query): threads waiting on long holds.
load 60 2000 & LOAD=$!
sleep 80; snap c-long-holds
kill $LOAD; pkill -P $LOAD 2>/dev/null || true; pkill -f "holdMs=2000" 2>/dev/null || true
echo "outputs in $OUT"
