#!/usr/bin/env bash
# Produces the runtime outputs shown in README.md ("What it looks like"): the sample app
# (springtune-samples/broken-config, budget 100 connections x 0.8 / 6 replicas = cap 13) under load, read through
# GET /actuator/tuning and GET /actuator/tuning/report.
#
#   mvn -q install -DskipTests -Dinvoker.skip -pl springtune-samples/broken-config -am
#   scripts/readme/capture-runtime-runs.sh OUT_DIR
#
# The saturation window is shortened from 5 minutes to 1 minute (springtune.hikari.window=1m) so the run takes
# about 8 minutes; rules and thresholds are otherwise the defaults. Needs curl and jq.
set -euo pipefail
OUT="$1"; mkdir -p "$OUT"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$(ls "$ROOT"/springtune-samples/broken-config/target/springtune-sample-broken-config-*.jar | head -1)"
PORT="${PORT:-18081}"; BASE="http://localhost:$PORT"
APP=""

start_app() {  # start_app LOG ARGS...
  local log="$1"; shift
  java -jar "$JAR" --server.port="$PORT" --springtune.hikari.window=1m "$@" > "$OUT/$log" 2>&1 &
  APP=$!
  until curl -fs "$BASE/actuator/health" >/dev/null 2>&1; do sleep 1; done
}
stop_app() { [ -z "$APP" ] || { kill "$APP" 2>/dev/null || true; wait "$APP" 2>/dev/null || true; APP=""; }; }

load_start() {  # load_start CLIENTS HOLD_MS: CLIENTS concurrent callers of /orders?holdMs=HOLD_MS
  rm -f "$OUT/stop"
  for _ in $(seq "$1"); do
    ( while [ ! -f "$OUT/stop" ]; do curl -fs -o /dev/null "$BASE/orders?holdMs=$2" || true; done ) &
  done
}
load_stop() { touch "$OUT/stop"; wait $(jobs -p | grep -vx "${APP:-x}") 2>/dev/null || true; }
trap 'touch "$OUT/stop"; stop_app' EXIT

snap() { curl -fs "$BASE/actuator/tuning" > "$OUT/$1.json"; }

# Pool of 20, as configured: above the budget cap of 13.
start_app app-pool20.log
sleep 12; snap a-started                         # A: just started, no traffic
load_start 60 50; sleep 80; snap b-short-holds   # B: 60 callers, 50 ms holds: threads wait on short holds
load_stop; sleep 70
load_start 60 2000; sleep 80; snap c-long-holds  # C: 60 callers, 2 s holds (a slow query)
load_stop; stop_app

# Pool of 8: inside the budget cap.
start_app app-pool8.log --spring.datasource.hikari.maximum-pool-size=8
load_start 40 50; sleep 80; snap d-short-holds-pool8   # D: 40 callers, 50 ms holds
curl -fs "$BASE/actuator/tuning/report" > "$OUT/d-report.json"
load_stop; stop_app
echo "outputs in $OUT"
