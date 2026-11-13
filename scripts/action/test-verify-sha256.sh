#!/usr/bin/env bash
# Tests scripts/action/verify-sha256.sh, the check the GitHub Action runs before it executes the CLI jar.
#
#   scripts/action/test-verify-sha256.sh JAR SHA256SUMS
#
# JAR and SHA256SUMS are a real release's CLI jar and checksum file (CI downloads v0.4.1's). Proves: the genuine
# jar verifies, also when SHA256SUMS lists more assets (the SBOM, in releases after 0.4.0); a tampered jar (one byte appended,
# one byte changed), a missing or empty SHA256SUMS, a SHA256SUMS without the jar's line, one with a wrong hash and
# one with two lines for the jar are all rejected with exit code 1.
set -euo pipefail

JAR="${1:?usage: test-verify-sha256.sh JAR SHA256SUMS}"
SUMS="${2:?usage: test-verify-sha256.sh JAR SHA256SUMS}"
VERIFY="$(cd "$(dirname "$0")" && pwd)/verify-sha256.sh"
NAME="$(basename "$JAR")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0

expect() {  # expect EXIT_CODE DESCRIPTION -- COMMAND...
  local want="$1" what="$2"
  shift 3
  local got=0
  "$@" > "$WORK/out.txt" 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then
    echo "PASS ($got): $what"
  else
    echo "FAIL: $what: exit $got, expected $want"
    sed 's/^/    /' "$WORK/out.txt"
    failures=$((failures + 1))
  fi
}

expect 0 "genuine $NAME verifies" -- "$VERIFY" "$JAR" "$SUMS"
grep -q "matches SHA256SUMS" "$WORK/out.txt" || { echo "FAIL: no success message"; failures=$((failures + 1)); }

# Releases after 0.4.0 add more assets (e.g. the CycloneDX SBOM) to SHA256SUMS: only the jar's line may count,
# wherever it is, and a near-miss name (prefix/suffix of the jar's) must not be picked.
{ printf '%064d  %s\n' 1 "springtune-0.0.0-sbom.cyclonedx.json"
  printf '%064d  %s\n' 2 "$NAME.asc"
  printf '%064d  %s\n' 3 "x-$NAME"
  cat "$SUMS"
  printf '%064d  %s\n' 4 "springtune-maven-repo-0.0.0.zip"; } > "$WORK/extra-lines"
expect 0 "SHA256SUMS with extra lines (SBOM, similar names) still verifies the jar by its exact name" -- "$VERIFY" "$JAR" "$WORK/extra-lines"
grep -v " $NAME\$" "$WORK/extra-lines" > "$WORK/extra-no-line" || true
expect 1 "SHA256SUMS with extra lines but no jar line is rejected" -- "$VERIFY" "$JAR" "$WORK/extra-no-line"

mkdir -p "$WORK/appended" "$WORK/flipped"
cp "$JAR" "$WORK/appended/$NAME"
printf 'x' >> "$WORK/appended/$NAME"
expect 1 "jar with one byte appended is rejected" -- "$VERIFY" "$WORK/appended/$NAME" "$SUMS"
grep -q "does not match SHA256SUMS" "$WORK/out.txt" || { echo "FAIL: no mismatch message"; failures=$((failures + 1)); }
expect 1 "tampered jar is rejected by a SHA256SUMS with extra lines" -- "$VERIFY" "$WORK/appended/$NAME" "$WORK/extra-lines"

cp "$JAR" "$WORK/flipped/$NAME"
orig="$(od -An -tu1 -j1000 -N1 "$JAR" | tr -d ' ')"
# shellcheck disable=SC2059
printf "\\$(printf '%03o' $(( (orig + 1) % 256 )))" | dd of="$WORK/flipped/$NAME" bs=1 seek=1000 conv=notrunc status=none
expect 1 "jar with one byte changed is rejected" -- "$VERIFY" "$WORK/flipped/$NAME" "$SUMS"

expect 1 "missing SHA256SUMS is rejected" -- "$VERIFY" "$JAR" "$WORK/does-not-exist"
: > "$WORK/empty"
expect 1 "empty SHA256SUMS is rejected" -- "$VERIFY" "$JAR" "$WORK/empty"

grep -v " $NAME\$" "$SUMS" > "$WORK/no-line" || true
expect 1 "SHA256SUMS without the jar's line is rejected" -- "$VERIFY" "$JAR" "$WORK/no-line"

printf '%064d  %s\n' 0 "$NAME" > "$WORK/wrong-hash"
expect 1 "SHA256SUMS with a wrong hash is rejected" -- "$VERIFY" "$JAR" "$WORK/wrong-hash"

# CRLF line endings make the name field "<name>\r": no exact match, so it fails closed instead of guessing.
sed 's/$/\r/' "$SUMS" > "$WORK/crlf"
expect 1 "SHA256SUMS with CRLF line endings is rejected (fails closed)" -- "$VERIFY" "$JAR" "$WORK/crlf"

cat "$SUMS" "$SUMS" > "$WORK/duplicate"
expect 1 "SHA256SUMS with two lines for the jar is rejected" -- "$VERIFY" "$JAR" "$WORK/duplicate"

[ "$failures" -eq 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "All checksum verification checks passed"
