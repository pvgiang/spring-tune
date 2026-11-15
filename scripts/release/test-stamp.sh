#!/usr/bin/env bash
# Tests scripts/release/stamp-action-version.sh on copies of the repository's action.yml. Changes nothing in the
# repository. Run by the "Release scripts" job in .github/workflows/ci.yml.
#
#   scripts/release/test-stamp.sh
#
# Proves: stamping changes exactly the `version` input's default and description lines and nothing else; the
# default reads back as the stamped version; a second run leaves the file byte-for-byte unchanged; a leading v is
# dropped; invalid versions exit 2 without touching the file; a file with no version input, no default, or two
# defaults exits 1 and is left unchanged.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
STAMP="$HERE/stamp-action-version.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }

run() {  # run WANT_EXIT DESCRIPTION FILE ARGS... : runs the stamp script, checks its exit code
  local want="$1" what="$2"; shift 2
  local got=0
  bash "$STAMP" "$@" > "$WORK/out.txt" 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then pass "$what (exit $got)"; else fail "$what: exit $got, expected $want"; sed 's/^/    /' "$WORK/out.txt"; fi
}

default_of() { awk '/^inputs:/ {i=1; next} i && /^[^ ]/ {i=0} i && /^  version:/ {v=1; next} v && /^  [^ ]/ {v=0} v && /^    default:/ {gsub(/[" '"'"']|default:/, ""); print; exit}' "$1"; }
line_of() { grep -n -- "$2" "$1" | head -1 | cut -d: -f1; }

# 1. Stamp a copy of the real action.yml with a version it does not default to.
cp "$ROOT/action.yml" "$WORK/action.yml"
before="$(default_of "$WORK/action.yml")"
target=9.8.7
[ "$before" != "$target" ] || target=9.8.6
run 0 "stamp $before -> $target" "$target" "$WORK/action.yml"
[ "$(default_of "$WORK/action.yml")" = "$target" ] && pass "default reads back as $target" \
  || fail "default reads '$(default_of "$WORK/action.yml")', expected $target"
changed="$({ diff "$ROOT/action.yml" "$WORK/action.yml" || true; } | sed -n 's/^\([0-9,]*\)c\([0-9,]*\)$/\1/p' | tr '\n' ' ')"
want_lines="$(line_of "$ROOT/action.yml" 'e\.g\. [0-9]'),$(line_of "$ROOT/action.yml" "default: \"$before\"")"
[ "$changed" = "$want_lines " ] && pass "only lines $want_lines changed (the version input's description and default)" \
  || fail "changed lines '$changed', expected '$want_lines '"
[ "$({ diff "$ROOT/action.yml" "$WORK/action.yml" || true; } | grep -c '^[<>]')" -eq 4 ] && pass "diff is 2 lines out, 2 lines in" \
  || fail "unexpected diff"
grep -q "e.g. $target (a release number only" "$WORK/action.yml" && pass "description example names $target" \
  || fail "description example not stamped"

# 2. Idempotent.
cp "$WORK/action.yml" "$WORK/once.yml"
run 0 "second stamp with $target" "$target" "$WORK/action.yml"
cmp -s "$WORK/action.yml" "$WORK/once.yml" && pass "second stamp leaves the file byte-for-byte unchanged" \
  || fail "second stamp changed the file"
grep -q "already defaults to CLI $target" "$WORK/out.txt" && pass "second stamp says 'already'" || fail "no 'already' message"

# 3. A leading v is dropped; stamping back restores the original bytes.
run 0 "stamp v$before (leading v)" "v$before" "$WORK/action.yml"
cmp -s "$ROOT/action.yml" "$WORK/action.yml" && pass "stamping back to $before restores the original file" \
  || fail "stamping back did not restore the original"

# 4. Invalid versions: exit 2, file untouched.
for bad in latest 0.4 "0.4.1/../../x" "1.2.3 x" "" "1.2.3;id"; do
  cp "$ROOT/action.yml" "$WORK/bad.yml"
  run 2 "invalid version '$bad' refused" "$bad" "$WORK/bad.yml"
  cmp -s "$ROOT/action.yml" "$WORK/bad.yml" || fail "invalid version '$bad' changed the file"
done
run 2 "no arguments refused"
run 2 "missing file refused" 1.2.3 "$WORK/does-not-exist.yml"

# 5. Malformed action files: exit 1, file untouched.
printf 'name: x\ninputs:\n  path:\n    default: "."\nruns:\n  using: composite\n' > "$WORK/noversion.yml"
cp "$WORK/noversion.yml" "$WORK/noversion.orig"
run 1 "no version input" 1.2.3 "$WORK/noversion.yml"
cmp -s "$WORK/noversion.yml" "$WORK/noversion.orig" || fail "file without a version input was changed"

printf 'inputs:\n  version:\n    description: "e.g. 0.1.0"\n  path:\n    default: "."\n' > "$WORK/nodefault.yml"
cp "$WORK/nodefault.yml" "$WORK/nodefault.orig"
run 1 "version input without a default (the next input's default is not taken)" 1.2.3 "$WORK/nodefault.yml"
cmp -s "$WORK/nodefault.yml" "$WORK/nodefault.orig" || fail "file without a version default was changed"

printf 'inputs:\n  version:\n    default: "0.1.0"\n    default: "0.1.0"\n' > "$WORK/twodefaults.yml"
cp "$WORK/twodefaults.yml" "$WORK/twodefaults.orig"
run 1 "two default lines" 1.2.3 "$WORK/twodefaults.yml"
cmp -s "$WORK/twodefaults.yml" "$WORK/twodefaults.orig" || fail "file with two defaults was changed"

# 6. A version key outside `inputs:` (an output, a step's with:) is never touched.
printf 'outputs:\n  version:\n    default: "0.1.0"\ninputs:\n  version:\n    description: "no example"\n    default: 0.1.0\n' > "$WORK/scoped.yml"
run 0 "only the input under inputs: is stamped; an unquoted default gets quotes" 2.0.0 "$WORK/scoped.yml"
[ "$(sed -n 3p "$WORK/scoped.yml")" = '    default: "0.1.0"' ] && pass "outputs.version untouched" || fail "outputs.version changed"
[ "$(sed -n 7p "$WORK/scoped.yml")" = '    default: "2.0.0"' ] && pass "inputs.version stamped" || fail "inputs.version: $(sed -n 7p "$WORK/scoped.yml")"
[ "$(sed -n 6p "$WORK/scoped.yml")" = '    description: "no example"' ] && pass "description without an example untouched" \
  || fail "description changed"

if [ "$failures" -gt 0 ]; then echo "test-stamp: $failures failure(s)"; exit 1; fi
echo "test-stamp: all passed"
