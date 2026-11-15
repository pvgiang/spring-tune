#!/usr/bin/env bash
# Tests scripts/release/release-commit.sh (the Release workflow's tag step) against a local bare repository standing
# in for origin. Changes nothing in this repository; no network. Run by the "Release scripts" job in ci.yml.
#
#   scripts/release/test-release-commit.sh
#
# Proves, for dispatch: the tag points to a one-file commit whose parent is the built commit and whose action.yml
# defaults to the version; no branch on origin moves; a re-run reuses that tag; a tag that exists on another commit,
# or on a commit that changes more than action.yml, is refused; missing release notes are refused; a built commit
# that already defaults to the version is tagged as it is. For a pushed tag: refused unless already stamped.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SCRIPT="$HERE/release-commit.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }
G=(-c user.name=test -c user.email=test@example.invalid -c init.defaultBranch=main -c advice.detachedHead=false)

git "${G[@]}" init -q --bare "$WORK/origin.git"
git "${G[@]}" init -q "$WORK/repo"
cd "$WORK/repo"
cp "$ROOT/action.yml" action.yml
mkdir -p docs/releases
for v in 9.9.9 9.9.7 9.9.6 9.9.5; do echo "notes $v" > "docs/releases/v$v.md"; done
echo "notes" > "docs/releases/v$(awk '/^inputs:/ {i=1; next} i && /^  version:/ {v=1; next} v && /^    default:/ {gsub(/[" ]|default:/, ""); print; exit}' action.yml).md"
git add -A
git "${G[@]}" commit -q -m built
git remote add origin "$WORK/origin.git"
git push -q origin HEAD:refs/heads/main 2> /dev/null
BUILT="$(git rev-parse HEAD)"
DEFAULT="$(git show HEAD:action.yml | awk '/^inputs:/ {i=1; next} i && /^  version:/ {v=1; next} v && /^    default:/ {gsub(/[" ]|default:/, ""); print; exit}')"

run() {  # run WANT DESCRIPTION ARGS...: runs the script with a fresh GITHUB_OUTPUT
  local want="$1" what="$2" rc=0; shift 2
  : > "$WORK/output"
  GITHUB_OUTPUT="$WORK/output" RUNNER_TEMP="$WORK" bash "$SCRIPT" "$@" > "$WORK/out.txt" 2>&1 || rc=$?
  if [ "$rc" -eq "$want" ]; then pass "$what (exit $rc)"; else fail "$what: exit $rc, expected $want"; sed 's/^/    /' "$WORK/out.txt"; fi
}
peeled() { git ls-remote origin "refs/tags/$1^{}" | cut -f1; }
default_at() { git show "$1:action.yml" | awk '/^inputs:/ {i=1; next} i && /^  version:/ {v=1; next} v && /^    default:/ {gsub(/[" ]|default:/, ""); print; exit}'; }

# 1. Dispatch 9.9.9 on the built commit.
run 0 "dispatch 9.9.9" 9.9.9 "$BUILT" dispatch
git fetch -q origin "+refs/tags/*:refs/tags/*"
S="$(peeled v9.9.9)"
[ -n "$S" ] && [ "$S" != "$BUILT" ] && pass "v9.9.9 is a new commit ($S)" || fail "v9.9.9 resolves to '$S'"
[ "$(git rev-parse "$S^")" = "$BUILT" ] && pass "its parent is the built commit" || fail "parent is $(git rev-parse "$S^")"
[ "$(git diff --name-only "$BUILT" "$S")" = action.yml ] && pass "it changes action.yml only" || fail "changed: $(git diff --name-only "$BUILT" "$S")"
[ "$(default_at "$S")" = 9.9.9 ] && pass "its action.yml defaults to 9.9.9" || fail "default at tag: $(default_at "$S")"
[ "$(git diff -U0 "$BUILT" "$S" -- action.yml | grep -c '^+[^+]')" -eq 2 ] && pass "2 lines changed (version input's description and default)" \
  || fail "lines changed: $(git diff -U0 "$BUILT" "$S" -- action.yml)"
[ "$(git cat-file -t "$(git ls-remote origin refs/tags/v9.9.9 | cut -f1)")" = tag ] && pass "the tag is annotated" || fail "tag is not annotated"
[ "$(git log -1 --format=%an "$S")" = "github-actions[bot]" ] && pass "release commit author is github-actions[bot]" || fail "author: $(git log -1 --format=%an "$S")"
grep -qx "sha=$S" "$WORK/output" && pass "GITHUB_OUTPUT has sha=$S" || fail "GITHUB_OUTPUT: $(cat "$WORK/output")"
[ "$(git ls-remote origin refs/heads/main | cut -f1)" = "$BUILT" ] && pass "origin's main did not move" || fail "origin main moved"
[ "$(git ls-remote --heads origin | wc -l)" -eq 1 ] && pass "no branch was pushed" || fail "branches: $(git ls-remote --heads origin)"
[ "$(git rev-parse HEAD)" = "$BUILT" ] && git diff --quiet && pass "the checkout is untouched (HEAD and working tree)" || fail "checkout changed"

# 2. Re-run: reuse.
run 0 "dispatch 9.9.9 again (re-run after the tag push)" 9.9.9 "$BUILT" dispatch
grep -qx "sha=$S" "$WORK/output" && grep -q 'reusing it' "$WORK/out.txt" && pass "re-run reuses the same release commit" || fail "re-run: $(cat "$WORK/out.txt")"

# 3. No notes.
run 1 "dispatch 9.9.8 without docs/releases/v9.9.8.md" 9.9.8 "$BUILT" dispatch
[ -z "$(git ls-remote origin refs/tags/v9.9.8)" ] && pass "no tag v9.9.8 pushed" || fail "v9.9.8 was pushed"

# 4. An existing tag on the built commit itself (not stamped): refused.
git push -q origin "$BUILT:refs/tags/v9.9.7" 2> /dev/null
run 1 "dispatch 9.9.7 when v9.9.7 already exists on an unstamped commit" 9.9.7 "$BUILT" dispatch

# 5. An existing tag on a commit that changes more than action.yml: refused.
git checkout -q --detach "$BUILT"
bash "$HERE/stamp-action-version.sh" 9.9.6 action.yml > /dev/null
echo extra > extra.txt
git add -A
git "${G[@]}" commit -q -m "stamp and more"
git push -q origin "HEAD:refs/tags/v9.9.6" 2> /dev/null
git checkout -q --detach "$BUILT"
run 1 "dispatch 9.9.6 when v9.9.6 exists on a commit that also adds a file" 9.9.6 "$BUILT" dispatch
grep -q 'changes more than action.yml' "$WORK/out.txt" && pass "names the extra change" || fail "message: $(cat "$WORK/out.txt")"

# 6. Pushed tags.
run 1 "push mode: tag on a commit defaulting to $DEFAULT, releasing 9.9.9" 9.9.9 "$BUILT" push
grep -q "release through Actions > Release > Run workflow" "$WORK/out.txt" && pass "push mode says how to release instead" || fail "message: $(cat "$WORK/out.txt")"
run 0 "push mode: tag on the stamped commit" 9.9.9 "$S" push

# 7. A built commit that already defaults to the version is tagged as it is.
run 0 "dispatch $DEFAULT (the built commit already defaults to it)" "$DEFAULT" "$BUILT" dispatch
[ "$(peeled "v$DEFAULT")" = "$BUILT" ] && pass "v$DEFAULT is the built commit itself" || fail "v$DEFAULT resolves to $(peeled "v$DEFAULT")"

# 8. A shallow checkout (actions/checkout's default depth 1), as in the Release job.
git clone -q --depth 1 "file://$WORK/origin.git" "$WORK/shallow" 2> /dev/null
cd "$WORK/shallow"
[ "$(git rev-parse --is-shallow-repository)" = true ] && pass "shallow clone" || fail "clone is not shallow"
run 0 "dispatch 9.9.5 from a shallow clone" 9.9.5 "$BUILT" dispatch
S5="$(peeled v9.9.5)"
[ "$(git rev-parse "$S5^")" = "$BUILT" ] && [ "$(default_at "$S5")" = 9.9.5 ] && pass "v9.9.5: child of the built commit, defaults to 9.9.5" \
  || fail "v9.9.5 resolves to $S5"

# 9. Usage.
run 2 "invalid version" "latest" "$BUILT" dispatch
run 2 "unknown mode" 9.9.9 "$BUILT" retag

if [ "$failures" -gt 0 ]; then echo "test-release-commit: $failures failure(s)"; exit 1; fi
echo "test-release-commit: all passed"
