#!/usr/bin/env bash
# SpringTune PetClinic benchmark (docs/benchmarks/petclinic.md). Pre-registered scenarios; reports rule outcomes, not
# latency or throughput.
#
#   scripts/petclinic/run.sh build                 # clone PetClinic at the pinned SHA, apply the patch, package it
#   scripts/petclinic/run.sh env                   # print the environment record (nproc, java, PostgreSQL, SHAs)
#   scripts/petclinic/run.sh calibrate             # S2 rate calibration (pool 2, 100..1200 req/s, 40 s each)
#   scripts/petclinic/run.sh run S1|S2|S3|S4 <n>   # one scenario run: fresh PostgreSQL + app, load, polls, stop
#
# Environment:
#   WORK               work directory for the PetClinic checkout and logs (default: $TMPDIR/springtune-petclinic)
#   PG_ROOT            parent of the PostgreSQL data directories; must be writable by the OS user `postgres`
#                      (default /var/tmp/springtune-petclinic-pg)
#   PG_BIN             PostgreSQL 16 binaries (default /usr/lib/postgresql/16/bin)
#   PG_PORT            PostgreSQL port (default 55433)
#   APP_PORT           PetClinic HTTP port (default 18080)
#   SPRINGTUNE_VERSION starter version (default 0.4.0, from Maven Central)
#   OUT                output root (default docs/benchmarks/petclinic/<date>-runs in this repository)
#   S2_RATE            S2 load rate in req/s (required for S2: the result of `calibrate`, see the pre-registration)
#   LOAD_SECONDS       load duration per run (default 450 = 7.5 min), POLL_SECONDS poll interval (default 30)
#
# Needs: git, mvn, java 17+, jq, curl, sudo or root (PostgreSQL runs as `postgres`). Ports are never shared with
# another PostgreSQL: the script refuses to start if PG_PORT is in use.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
PETCLINIC_URL="https://github.com/spring-projects/spring-petclinic.git"
PETCLINIC_SHA="500158f732419217507c7656904b8e6aa1bcc0d6"
WORK="${WORK:-${TMPDIR:-/tmp}/springtune-petclinic}"
PG_ROOT="${PG_ROOT:-/var/tmp/springtune-petclinic-pg}"
PG_BIN="${PG_BIN:-/usr/lib/postgresql/16/bin}"
PG_PORT="${PG_PORT:-55433}"
APP_PORT="${APP_PORT:-18080}"
SPRINGTUNE_VERSION="${SPRINGTUNE_VERSION:-0.4.0}"
OUT="${OUT:-$REPO_ROOT/docs/benchmarks/petclinic/$(date -u +%Y-%m-%d)-runs}"
LOAD_SECONDS="${LOAD_SECONDS:-450}"
POLL_SECONDS="${POLL_SECONDS:-30}"
BASE="http://localhost:$APP_PORT"
SRC="$WORK/spring-petclinic"

as_postgres() {
  if [ "$(id -u)" = 0 ]; then su postgres -s /bin/bash -c "$*"; else sudo -u postgres bash -c "$*"; fi
}
log() { echo "[$(date -u +%H:%M:%S)] $*" >&2; }

build() {
  mkdir -p "$WORK"
  if [ ! -d "$SRC/.git" ]; then
    git clone -q "$PETCLINIC_URL" "$SRC"
  fi
  git -C "$SRC" fetch -q origin "$PETCLINIC_SHA" 2>/dev/null || true
  git -C "$SRC" checkout -q --force "$PETCLINIC_SHA"
  git -C "$SRC" reset -q --hard "$PETCLINIC_SHA"
  git -C "$SRC" apply "$HERE/petclinic.patch"
  (cd "$SRC" && mvn -B -ntp -q package -DskipTests -Dspring-javaformat.skip=true -Dcheckstyle.skip=true \
      -Dspringtune.version="$SPRINGTUNE_VERSION")
  ls "$SRC"/target/spring-petclinic-*.jar >/dev/null
  log "built $(ls "$SRC"/target/spring-petclinic-*.jar) with springtune-spring-boot-starter $SPRINGTUNE_VERSION"
}

jar() { ls "$SRC"/target/spring-petclinic-*.jar | grep -v plain | head -n 1; }

environment() {
  echo "date_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "nproc: $(nproc)"
  echo "memory: $(free -h | awk '/^Mem:/ {print $2}')"
  echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
  echo "kernel: $(uname -r)"
  echo "java: $(java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS | tr '\n' ' ')"
  echo "postgresql: $("$PG_BIN/postgres" --version)"
  echo "petclinic: $PETCLINIC_URL @ $PETCLINIC_SHA"
  echo "petclinic_boot: $(grep -A1 '<artifactId>spring-boot-starter-parent' "$SRC/pom.xml" | sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p')"
  echo "springtune_starter: io.github.winkytek.springtune:springtune-spring-boot-starter:$SPRINGTUNE_VERSION"
  local resolved="$HOME/.m2/repository/io/github/winkytek/springtune/springtune-spring-boot-autoconfigure/$SPRINGTUNE_VERSION"
  if [ -f "$resolved/_remote.repositories" ]; then
    echo "springtune_resolved_from: $(grep -o '>[a-z-]*=' "$resolved/_remote.repositories" | head -1 | tr -d '>=')"
  fi
  if [ -f "$resolved/springtune-spring-boot-autoconfigure-$SPRINGTUNE_VERSION.jar" ]; then
    echo "springtune_autoconfigure_sha256: $(sha256sum "$resolved/springtune-spring-boot-autoconfigure-$SPRINGTUNE_VERSION.jar" | cut -d' ' -f1)"
  fi
  echo "spring_tune_commit: $(git -C "$REPO_ROOT" rev-parse HEAD)"
}

pg_start() {
  local name="$1"
  if (exec 3<>"/dev/tcp/127.0.0.1/$PG_PORT") 2>/dev/null; then
    echo "run.sh: port $PG_PORT is in use; refusing to touch another PostgreSQL" >&2; exit 1
  fi
  PGDATA="$PG_ROOT/$name"
  mkdir -p "$PG_ROOT"; chown postgres "$PG_ROOT" 2>/dev/null || true
  as_postgres "rm -rf '$PGDATA' && '$PG_BIN/initdb' -A trust -U postgres -D '$PGDATA' >/dev/null"
  as_postgres "'$PG_BIN/pg_ctl' -D '$PGDATA' -l '$PGDATA/server.log' -w -o '-p $PG_PORT -k /tmp -c listen_addresses=127.0.0.1' start >/dev/null"
  as_postgres "'$PG_BIN/psql' -q -h 127.0.0.1 -p $PG_PORT -U postgres -c \"CREATE ROLE petclinic LOGIN PASSWORD 'petclinic'\" -c 'CREATE DATABASE petclinic OWNER petclinic'"
  log "PostgreSQL up on $PG_PORT ($PGDATA)"
}

pg_stop() {
  [ -n "${PGDATA:-}" ] && as_postgres "'$PG_BIN/pg_ctl' -D '$PGDATA' -m fast -w stop >/dev/null" 2>/dev/null || true
  PGDATA=""
}

APP_PID=""
LOCKER_PID=""
app_start() {
  local logfile="$1"; shift
  java -Xmx1g -jar "$(jar)" --spring.profiles.active=postgres --server.port="$APP_PORT" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$PG_PORT/petclinic" \
    --springtune.mode=advise "$@" >"$logfile" 2>&1 &
  APP_PID=$!
  local i
  for i in $(seq 1 120); do
    if curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      log "PetClinic up (pid $APP_PID) with: $*"; return 0
    fi
    kill -0 "$APP_PID" 2>/dev/null || { tail -n 30 "$logfile" >&2; echo "run.sh: PetClinic exited" >&2; exit 1; }
    sleep 1
  done
  echo "run.sh: PetClinic did not become healthy" >&2; tail -n 30 "$logfile" >&2; exit 1
}

app_stop() {
  if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; fi
  APP_PID=""
}

locker_stop() {
  if [ -n "$LOCKER_PID" ]; then kill "$LOCKER_PID" 2>/dev/null || true; wait "$LOCKER_PID" 2>/dev/null || true; fi
  LOCKER_PID=""
}

cleanup() { locker_stop; app_stop; pg_stop; }
trap cleanup EXIT

# S3: an external psql session holds ACCESS EXCLUSIVE on owners for 2.85 s of every 3 s.
locker_start() {
  local seconds="$1" script="$WORK/locker.sql" i
  : >"$script"
  for i in $(seq 1 $(( seconds / 3 + 20 ))); do
    echo "BEGIN; LOCK TABLE owners IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(2.85); COMMIT; SELECT pg_sleep(0.15);" >>"$script"
  done
  "$PG_BIN/psql" -q -X -h 127.0.0.1 -p "$PG_PORT" -U petclinic -d petclinic -f "$script" >/dev/null 2>"$WORK/locker.err" &
  LOCKER_PID=$!
  log "lock holder started (pid $LOCKER_PID)"
}

# Keeps only the parts of GET /actuator/tuning that the results use: recommendations, apply plans, the Hikari
# component's signals and knobs, and the budget. The executor components are kept with their signals too.
trim_snapshot() {
  jq --arg t "$1" '{elapsed_seconds: ($t | tonumber), mode, generatedAt, budget,
      components: [.components[] | {id, samples, signals, knobs: [.knobs[]? | {id, currentValue}]}],
      recommendations, applyPlans}'
}

poll_loop() {
  local dir="$1" seconds="$2" t=0
  : >"$dir/polls.jsonl"
  while [ "$t" -le "$seconds" ]; do
    local snap cpu
    snap="$(curl -fsS "$BASE/actuator/tuning" 2>/dev/null || echo '{}')"
    cpu="$(curl -fsS "$BASE/actuator/metrics/system.cpu.usage" 2>/dev/null | jq '.measurements[0].value' 2>/dev/null || echo null)"
    if [ "$snap" != '{}' ]; then
      printf '%s' "$snap" | trim_snapshot "$t" | jq -c --argjson cpu "${cpu:-null}" '. + {system_cpu_usage: $cpu}' >>"$dir/polls.jsonl"
      printf '%s' "$snap" >"$dir/last-snapshot-full.json"
    else
      jq -nc --arg t "$t" '{elapsed_seconds: ($t | tonumber), error: "GET /actuator/tuning failed"}' >>"$dir/polls.jsonl"
    fi
    local recs; recs="$(tail -n 1 "$dir/polls.jsonl" | jq -r '[.recommendations[]? | "\(.ruleId) \(.action) \(.currentValue)->\(.recommendedValue)"] | join(", ")')"
    log "t=${t}s cpu=${cpu} recommendations: [${recs}]"
    [ "$t" -ge "$seconds" ] && break
    sleep "$POLL_SECONDS"; t=$((t + POLL_SECONDS))
  done
}

run_scenario() {
  local scenario="$1" n="$2" rate seed args=()
  case "$scenario" in
    S1) rate=50;  seed=$((61000 + n)) ;;
    S2) rate="${S2_RATE:?S2 needs S2_RATE (from calibrate)}"; seed=$((62000 + n))
        args=(--spring.datasource.hikari.maximum-pool-size=2) ;;
    S3) rate=50;  seed=$((63000 + n)) ;;
    S4) rate=5;   seed=$((64000 + n)); args=(--springtune.hikari.low-utilization-window=5m) ;;
    *) echo "run.sh: unknown scenario $scenario" >&2; exit 2 ;;
  esac
  local dir="$OUT/$(echo "$scenario" | tr 'A-Z' 'a-z')/run-$n"
  mkdir -p "$dir"
  environment >"$dir/environment.txt"
  jq -n --arg s "$scenario" --argjson n "$n" --argjson rate "$rate" --argjson seed "$seed" \
        --argjson load "$LOAD_SECONDS" --argjson poll "$POLL_SECONDS" --arg args "${args[*]:-}" \
        --arg v "$SPRINGTUNE_VERSION" \
    '{scenario: $s, run: $n, rate_per_second: $rate, seed: $seed, load_seconds: $load, poll_seconds: $poll,
      app_args: $args, springtune_version: $v,
      lock_holder: (if $s == "S3" then "LOCK TABLE owners IN ACCESS EXCLUSIVE MODE for 2.85 s of every 3 s" else null end)}' \
    >"$dir/scenario.json"
  pg_start "pg-$scenario-$n"
  app_start "$WORK/app-$scenario-$n.log" "${args[@]}"
  [ "$scenario" = S3 ] && locker_start "$LOAD_SECONDS"
  java "$HERE/LoadDriver.java" --base "$BASE" --rate "$rate" --duration "$LOAD_SECONDS" --seed "$seed" \
    --out "$dir/driver-summary.json" >"$WORK/driver-$scenario-$n.log" 2>&1 &
  local driver=$!
  poll_loop "$dir" "$LOAD_SECONDS"
  wait "$driver" || log "driver exited non-zero"
  locker_stop
  grep -E "SpringTune|springtune" "$WORK/app-$scenario-$n.log" | grep -v DEBUG | head -n 20 >"$dir/app-springtune-log.txt" || true
  app_stop; pg_stop
  log "done: $dir"
}

calibrate() {
  local dir="$OUT/s2-calibration" seed=62000 step
  mkdir -p "$dir"
  environment >"$dir/environment.txt"
  pg_start "pg-calibration"
  app_start "$WORK/app-calibration.log" --spring.datasource.hikari.maximum-pool-size=2
  : >"$dir/steps.jsonl"
  for step in 100 200 400 800 1200; do
    java "$HERE/LoadDriver.java" --base "$BASE" --rate "$step" --duration 40 --seed "$seed" \
      --out "$dir/driver-$step.json" >"$WORK/driver-calibration-$step.log" 2>&1 &
    local driver=$! i polls=0 waiting=0 v
    sleep 10
    for i in $(seq 1 30); do
      v="$(curl -fsS "$BASE/actuator/metrics/hikaricp.connections.pending" | jq '.measurements[0].value')"
      polls=$((polls + 1)); if awk "BEGIN {exit !($v > 0)}"; then waiting=$((waiting + 1)); fi
      sleep 1
    done
    wait "$driver" || true
    jq -nc --argjson r "$step" --argjson p "$polls" --argjson w "$waiting" \
      '{rate_per_second: $r, polls: $p, polls_with_pending: $w, share: ($w / $p)}' | tee -a "$dir/steps.jsonl" >&2
    sleep 5
  done
  local chosen
  chosen="$(jq -s '[.[] | select(.share >= 0.95)][0].rate_per_second // 1200' "$dir/steps.jsonl")"
  echo "{\"rule\": \"lowest step with pending > 0 in >= 95% of polls, else 1200\", \"s2_rate\": $chosen}" >"$dir/result.json"
  log "S2 rate: $chosen req/s"
  app_stop; pg_stop
}

case "${1:-}" in
  build) build ;;
  env) environment ;;
  calibrate) calibrate ;;
  run) run_scenario "${2:?scenario}" "${3:?run number}" ;;
  *) sed -n '2,22p' "$0"; exit 2 ;;
esac
