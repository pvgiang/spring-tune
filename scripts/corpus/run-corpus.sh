#!/usr/bin/env bash
# Runs `springtune check` over the corpus. Static analysis only: no corpus code is built or executed.
# usage: scripts/corpus/run-corpus.sh <work-dir> <out-dir>
#   work-dir  where repos are cloned (shallow); existing clones are reused
#   out-dir   receives <owner>__<repo>.json reports, shas.tsv and exit-codes.tsv
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$1"; OUT="$2"
mkdir -p "$WORK" "$OUT"
JAR=$(ls "$ROOT"/springtune-check-cli/target/springtune-check-cli-*-all.jar | head -1)
: > "$OUT/shas.tsv"; : > "$OUT/exit-codes.tsv"
grep -vE '^\s*(#|$)' "$HERE/repos.txt" | while read -r slug; do
  dir="$WORK/${slug/\//__}"
  if [ ! -d "$dir/.git" ]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone -q --depth 1 "https://github.com/$slug" "$dir"
  fi
  printf '%s\t%s\n' "$slug" "$(git -C "$dir" rev-parse HEAD)" >> "$OUT/shas.tsv"
  set +e
  java -jar "$JAR" check "$dir" --fail-on NONE --quiet --format json --output "$OUT/tmp" > /dev/null 2> "$OUT/${slug/\//__}.err"
  code=$?
  set -e
  [ -f "$OUT/tmp/springtune-report.json" ] && mv "$OUT/tmp/springtune-report.json" "$OUT/${slug/\//__}.json"
  printf '%s\t%s\n' "$slug" "$code" >> "$OUT/exit-codes.tsv"
  echo "$slug exit=$code"
done
rm -rf "$OUT/tmp"
