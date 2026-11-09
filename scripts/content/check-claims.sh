#!/usr/bin/env bash
# Guards launch content (iteration-5 brief rows 5, 6 and 14).
#
# usage: scripts/content/check-claims.sh [dir-or-file ...]
#   default: docs/launch docs/deep-dives (relative to the repository root)
#
# Fails (exit 1) on:
#   1. banned wording: apply mode must never be oversold;
#   2. an install command that names a SpringTune version other than $EXPECTED_VERSION (default 0.4.0):
#      Action refs and `version:` inputs, release download URLs, CLI jar names, Central paths, Gradle plugin ids,
#      Maven <version> right after a springtune artifactId, groupId:artifactId:version coordinates;
#   3. the pre-0.3.0 groupId io.github.winky.springtune.
# Exit 2 on usage errors (a path that does not exist, nothing to scan).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXPECTED="${EXPECTED_VERSION:-0.4.0}"
if [ "$#" -eq 0 ]; then set -- "$ROOT/docs/launch" "$ROOT/docs/deep-dives"; fi

files=()
for p in "$@"; do
  if [ -d "$p" ]; then
    while IFS= read -r -d '' f; do files+=("$f"); done < <(find "$p" -type f \( -name '*.md' -o -name '*.markdown' -o -name '*.txt' -o -name '*.yml' -o -name '*.yaml' \) -print0 | sort -z)
  elif [ -f "$p" ]; then
    files+=("$p")
  else
    echo "check-claims: no such file or directory: $p" >&2
    exit 2
  fi
done
if [ "${#files[@]}" -eq 0 ]; then
  echo "check-claims: nothing to scan in: $*" >&2
  exit 2
fi

# Case-insensitive; hyphen, space or nothing between the parts.
BANNED='production[- ]?ready|self[- ]?tun(e|es|ed|ing)|safe[- ]?auto[- ]?appl(y|ies|ied|ying)'

hits=0
for f in "${files[@]}"; do
  rel="${f#"$ROOT"/}"
  while IFS= read -r line; do
    echo "$rel:$line  [banned wording]"
    hits=$((hits + 1))
  done < <(grep -n -i -E "$BANNED" "$f" || true)

  while IFS= read -r line; do
    echo "$rel:$line"
    hits=$((hits + 1))
  done < <(awk -v want="$EXPECTED" '
    function check(ver, what) {
      if (ver != want) printf "%d: %s names version %s, expected %s  [install version]\n", NR, what, ver, want
    }
    {
      line = $0
      # Action ref: pvgiang/spring-tune@v1.2.3 (a branch or SHA ref is reported too)
      if (match(line, /pvgiang\/spring-tune@[^ "'"'"'`]+/)) {
        ref = substr(line, RSTART + 20, RLENGTH - 20); sub(/^v/, "", ref)
        check(ref, "Action ref"); action_block = 8
      } else if (action_block > 0) {
        action_block--
        if (match(line, /^[ \t-]*uses:/)) action_block = 0
        else if (match(line, /^[ \t]*version:[ \t]*["'"'"']?[0-9][^ "'"'"'#]*/)) {
          v = substr(line, RSTART, RLENGTH); sub(/^[ \t]*version:[ \t]*["'"'"']?/, "", v)
          check(v, "Action version input")
        }
      }
      # Release download URL: releases/download/v1.2.3/
      s = line
      while (match(s, /releases\/download\/v[0-9][0-9A-Za-z.+-]*\//)) {
        v = substr(s, RSTART + 19, RLENGTH - 20); check(v, "release download URL"); s = substr(s, RSTART + RLENGTH)
      }
      # Jar names: springtune-cli-1.2.3.jar, springtune-check-cli-1.2.3-all.jar
      s = line
      while (match(s, /springtune-(check-)?cli-[0-9][0-9A-Za-z.+]*(-SNAPSHOT)?(-all)?\.jar/)) {
        v = substr(s, RSTART, RLENGTH); sub(/^springtune-(check-)?cli-/, "", v); sub(/(-all)?\.jar$/, "", v)
        check(v, "CLI jar name"); s = substr(s, RSTART + RLENGTH)
      }
      # Central path: .../springtune/<artifact>/1.2.3/
      s = line
      while (match(s, /winkytek\/springtune\/[a-z.-]+\/[0-9][0-9A-Za-z.+-]*\//)) {
        v = substr(s, RSTART, RLENGTH); sub(/^winkytek\/springtune\/[a-z.-]+\//, "", v); sub(/\/$/, "", v)
        check(v, "Maven Central path"); s = substr(s, RSTART + RLENGTH)
      }
      # Gradle plugin id: id("io.github.winkytek.springtune") version "1.2.3"
      if (match(line, /io\.github\.winkytek\.springtune["'"'"')]+[ \t]+version[ \t]+["'"'"'][^"'"'"']+/)) {
        v = substr(line, RSTART, RLENGTH); sub(/^.*version[ \t]+["'"'"']/, "", v)
        check(v, "Gradle plugin version")
      }
      # Coordinates: io.github.winkytek.springtune:artifact:1.2.3
      s = line
      while (match(s, /io\.github\.winkytek\.springtune:[a-z-]+:[0-9][0-9A-Za-z.+-]*/)) {
        v = substr(s, RSTART, RLENGTH); sub(/^io\.github\.winkytek\.springtune:[a-z-]+:/, "", v)
        check(v, "Maven coordinate"); s = substr(s, RSTART + RLENGTH)
      }
      # Maven XML: <version> within 3 lines after <artifactId>springtune-...</artifactId>
      if (line ~ /<artifactId>springtune-[a-z-]+<\/artifactId>/) xml_block = 3
      if (xml_block > 0) {
        if (match(line, /<version>[^<]+<\/version>/)) {
          v = substr(line, RSTART + 9, RLENGTH - 19); check(v, "Maven <version>"); xml_block = 0
        } else if (line !~ /<artifactId>springtune-/) xml_block--
      }
      # Old groupId (0.1.0)
      if (line ~ /io\.github\.winky\.springtune/)
        printf "%d: pre-0.3.0 groupId io.github.winky.springtune  [install version]\n", NR
    }' "$f")
done

if [ "$hits" -gt 0 ]; then
  echo "check-claims: $hits problem(s) in ${#files[@]} file(s)" >&2
  exit 1
fi
echo "check-claims: OK (${#files[@]} files, install version $EXPECTED, no banned wording)"
