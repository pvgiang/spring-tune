#!/usr/bin/env bash
# Verifies a downloaded release file against the release's SHA256SUMS, as the GitHub Action does before it runs
# the CLI jar (action.yml). Only the line for NAME is checked, so other assets in SHA256SUMS do not matter.
#
#   scripts/action/verify-sha256.sh FILE SHA256SUMS [NAME]
#
# NAME is the file's name in SHA256SUMS (default: basename of FILE). Exit 0 when the hash matches; exit 1, with a
# message saying why, when SHA256SUMS is missing or empty, has no line (or more than one) for NAME, the hash is
# malformed, or FILE does not match. Uses sha256sum -c, or shasum -a 256 -c where sha256sum is missing (macOS).
# Tested by scripts/action/test-verify-sha256.sh.
set -euo pipefail

fail() {
  echo "::error::SpringTune checksum verification failed: $*" >&2
  exit 1
}

[ $# -ge 2 ] || { echo "usage: verify-sha256.sh FILE SHA256SUMS [NAME]" >&2; exit 2; }
file="$1"
sums="$2"
name="${3:-$(basename "$file")}"

[ -f "$file" ] || fail "$file does not exist"
[ -s "$sums" ] || fail "checksum file $sums is missing or empty; refusing to run an unverified $name"

# Lines are "<64 hex>  <name>" (text mode) or "<64 hex> *<name>" (binary mode).
lines="$(awk -v n="$name" '$2 == n || $2 == ("*" n)' "$sums")"
count="$(printf '%s' "$lines" | grep -c . || true)"
[ "$count" -eq 1 ] || fail "expected exactly one line for $name in $sums, found $count; refusing to run an unverified $name"
expected="$(printf '%s\n' "$lines" | awk '{print tolower($1)}')"
printf '%s' "$expected" | grep -Eq '^[0-9a-f]{64}$' || fail "malformed SHA-256 for $name in $sums: $expected"

if command -v sha256sum > /dev/null 2>&1; then
  check=(sha256sum -c -)
elif command -v shasum > /dev/null 2>&1; then
  check=(shasum -a 256 -c -)
else
  fail "neither sha256sum nor shasum is available"
fi

if ! printf '%s  %s\n' "$expected" "$file" | "${check[@]}"; then
  actual="$( (sha256sum "$file" 2> /dev/null || shasum -a 256 "$file") | awk '{print $1}')"
  fail "$name does not match SHA256SUMS (expected $expected, got $actual); the download is corrupt or was tampered with"
fi
echo "Verified $name: SHA-256 $expected matches SHA256SUMS"
