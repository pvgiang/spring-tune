#!/usr/bin/env bash
# Tests scripts/release/sweep-install-version.sh and scripts/check-install-strings.sh on copies of this tree. Changes
# nothing in the repository and needs no network (Maven Central's answers are faked with a stub curl). Run by the
# "Release scripts" job in .github/workflows/ci.yml.
#
#   scripts/release/test-sweep.sh
#
# Proves:
#   1. The templated stale list (STALE_VERSIONS x TEMPLATES) flags at least every line the hand-written list of
#      iteration 6 flagged, on a seeded copy; and nothing on this tree.
#   2. The sweep dry run 0.4.1 -> 0.4.2 --skip-central on a copy exits 0, after which that copy's
#      check-install-strings.sh and check-claims.sh pass; prose naming a measured version is unchanged; ci.yml's
#      stamped-copy job no longer equals main's default; a second run rewrites nothing; 0.4.10 is not taken for 0.4.1.
#   3. Without --skip-central the sweep exits 1 and changes nothing when Central answers 404 ("press Publish"),
#      429, 503 or nothing ("could not check Central (HTTP n), re-run"), and when Central answers 200 but the README
#      outputs are not re-captured. Usage errors exit 2.
#   4. The pom guard: pom 0.4.3-SNAPSHOT with notes v0.4.2 passes; 0.4.2-SNAPSHOT, 0.4.2 and 0.4.1-SNAPSHOT fail; in a
#      git checkout, 0.4.2-SNAPSHOT passes while origin has no v0.4.2 tag (release pending) and fails once it has.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }

copy_tree() {  # copy_tree DEST: this tree without .git and build output
  mkdir -p "$1"
  (cd "$ROOT" && tar --exclude=./.git --exclude='./*/target' --exclude=./target --exclude=./.claude -cf - .) | tar -xf - -C "$1"
}
tree_sum() { (cd "$1" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1); }

# ---------------------------------------------------------------------------------------------------------------
# 1. Templated list vs the hand-written list it replaced (scripts/check-install-strings.sh before iteration 7).
OLD_STALE=(
  '@v0.1.0' '@v0.3.0' 'springtune-cli-0.1.0' 'springtune-cli-0.3.0' '0.3.0</version>' 'version "0.3.0"'
  'springtune-maven-repo-0.'
  '@v0.4.0' 'springtune-cli-0.4.0' 'springtune-check-cli-0.4.0' 'check-cli/0.4.0' 'check-cli:0.4.0' '0.4.0</version>'
  '0.4.0</springtune.version>' 'version "0.4.0"' 'version: "0.4.0"' "version '0.4.0'" 'releases/download/v0.4.0/'
  'springtune-maven-plugin:0.4.0' 'springtune-gradle-plugin:0.4.0' 'springtune-spring-boot-starter:0.4.0'
  'springtune-limiter:0.4.0'
)
old_hits() {  # old_hits TREE: file:line for every line the old list flags
  (cd "$1" && mapfile -t fs < <(bash scripts/check-install-strings.sh --list-files .) \
    && for p in "${OLD_STALE[@]}"; do grep -nF -- "$p" "${fs[@]}" || true; done | cut -d: -f1,2 | sort -u)
}
new_hits() {  # new_hits TREE: file:line for every line the templated guard flags
  bash "$1/scripts/check-install-strings.sh" "$1" 2> /dev/null | sed -n "s/^::error file=[^:]*::stale install string '.*': \([^:]*:[0-9]*\):.*/\1/p" | sort -u || true
}

copy_tree "$WORK/seed"
seed="$WORK/seed/docs/seed-stale.md"
{
  # Each old pattern in the context it appears in when someone pastes an install line.
  for p in "${OLD_STALE[@]}"; do echo "    paste: $p x"; done
  echo '      - uses: pvgiang/spring-tune@v0.4.0'
  echo '  <version>0.3.0</version>'
  echo '    id("io.github.winkytek.springtune") version "0.4.0"'
  echo 'curl -fsSLO https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-check-cli/0.4.0/springtune-check-cli-0.4.0-all.jar'
  echo 'implementation("io.github.winkytek.springtune:springtune-spring-boot-starter:0.4.0")'
  echo "    id 'io.github.winkytek.springtune' version '0.4.0'"
  echo 'https://github.com/pvgiang/spring-tune/releases/download/v0.3.0/springtune-cli-0.3.0.jar'
  echo 'springtune-maven-repo-0.4.0.zip'
  # Strings the old list missed (0.1.0 and 0.3.0 had only some patterns): the templates catch them too.
  echo '          version: "0.3.0"'
  echo '<springtune.version>0.1.0</springtune.version>'
  echo 'https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-spring-boot-starter/0.4.0/'
  # Prose about a measurement: neither list may flag it.
  echo 'Measured with the 0.4.0 CLI on 2026-11-09 (the 2026-11-09-0.4.0 re-run).'
} > "$seed"
old_hits "$WORK/seed" > "$WORK/old.txt"
new_hits "$WORK/seed" > "$WORK/new.txt"
n_old="$(grep -c . "$WORK/old.txt" || true)"; n_new="$(grep -c . "$WORK/new.txt" || true)"
missed="$(comm -23 "$WORK/old.txt" "$WORK/new.txt")"
if [ -z "$missed" ] && [ "$n_old" -ge "${#OLD_STALE[@]}" ]; then
  pass "templated list flags every line the old list flags ($n_old old, $n_new new, on a seeded copy)"
else
  fail "templated list misses lines the old list flags: $missed"
fi
extra="$(comm -13 "$WORK/old.txt" "$WORK/new.txt" | tr '\n' ' ')"
[ "$(comm -13 "$WORK/old.txt" "$WORK/new.txt" | grep -c . || true)" -ge 3 ] && pass "templated list also flags what the old list missed: $extra" \
  || fail "templated list flags no more than the old list: $extra"
prose_line="docs/seed-stale.md:$(grep -n 'Measured with the 0.4.0 CLI' "$seed" | cut -d: -f1)"
grep -qx "$prose_line" "$WORK/new.txt" && fail "prose naming a measured version is flagged ($prose_line)" \
  || pass "prose naming a measured version is not flagged"
out="$(bash "$ROOT/scripts/check-install-strings.sh" "$ROOT" 2>&1)" && pass "this tree passes the guard: $out" \
  || fail "this tree fails the guard: $out"

# ---------------------------------------------------------------------------------------------------------------
# 2. Dry run 0.4.1 -> 0.4.2 on a copy.
copy_tree "$WORK/dry"
sum_repo_before="$(tree_sum "$ROOT/docs")"
rc=0; bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 --dry-run --skip-central "$WORK/dry" > "$WORK/dry.txt" 2>&1 || rc=$?
[ "$rc" -eq 0 ] && pass "dry run 0.4.1 -> 0.4.2 --skip-central exits 0 ($(grep -c '^sweep: .*:[0-9]*:$' "$WORK/dry.txt") lines rewritten)" \
  || { fail "dry run exit $rc"; sed 's/^/    /' "$WORK/dry.txt"; }
[ "$(tree_sum "$ROOT/docs")" = "$sum_repo_before" ] && pass "dry run left this repository's docs unchanged" || fail "dry run changed the repository"
out="$(bash "$WORK/dry/scripts/check-install-strings.sh" "$WORK/dry" 2>&1)" && pass "swept copy: $out" || fail "swept copy fails the install-strings guard: $out"
out="$(env -u EXPECTED_VERSION bash "$WORK/dry/scripts/content/check-claims.sh" 2>&1)" && pass "swept copy: $out" \
  || fail "swept copy fails check-claims: $out"
grep -q '^STALE_VERSIONS=(0.1.0 0.3.0 0.4.0 0.4.1)$' "$WORK/dry/scripts/check-install-strings.sh" && pass "0.4.1 appended to STALE_VERSIONS" \
  || fail "STALE_VERSIONS: $(grep '^STALE_VERSIONS' "$WORK/dry/scripts/check-install-strings.sh")"
grep -q 'default: "0.4.2"' "$WORK/dry/action.yml" && pass "action.yml defaults to 0.4.2" || fail "action.yml default not swept"
grep -q 'pvgiang/spring-tune@v0.4.2' "$WORK/dry/README.md" && pass "README Action snippet pins @v0.4.2" || fail "README pin not swept"
grep -q 'the v0.4.2 tag defaults to CLI 0.4.1' "$WORK/dry/README.md" && pass "README snippet comment: the v0.4.2 tag defaults to CLI 0.4.1" \
  || fail "README lag comment not swept"
grep -q 'STAMP_VERSION: "0.4.1"' "$WORK/dry/.github/workflows/ci.yml" && pass "ci.yml stamped-copy job now stamps 0.4.1 (main defaults to 0.4.2)" \
  || fail "ci.yml STAMP_VERSION not set to FROM"
[ "$(grep -c '# sweep: current' "$WORK/dry/.github/workflows/ci.yml")" -eq "$(grep '# sweep: current' "$WORK/dry/.github/workflows/ci.yml" | grep -c '0\.4\.2')" ] \
  && pass "every ci.yml line marked '# sweep: current' names 0.4.2" || fail "a '# sweep: current' line in ci.yml does not name 0.4.2"
grep -q 'EXPECTED="${EXPECTED_VERSION:-0.4.2}"' "$WORK/dry/scripts/content/check-claims.sh" && pass "check-claims EXPECTED_VERSION default 0.4.2" \
  || fail "check-claims EXPECTED line not swept"
diff <(grep -v '^EXPECTED=' "$ROOT/scripts/content/check-claims.sh") <(grep -v '^EXPECTED=' "$WORK/dry/scripts/content/check-claims.sh") > /dev/null \
  && pass "check-claims.sh: only the EXPECTED= line changed" || fail "check-claims.sh changed beyond the EXPECTED= line"
for prose in 'the 0.4.0 CLI' '0.4.1 CLI from the v0.4.1 GitHub release' 'the 0.4.1 behaviour'; do
  a="$(grep -rF -- "$prose" "$ROOT/docs" "$ROOT/README.md" | wc -l)"; b="$(grep -rF -- "$prose" "$WORK/dry/docs" "$WORK/dry/README.md" | wc -l)"
  [ "$a" -eq "$b" ] && pass "prose '$prose' unchanged ($a line(s))" || fail "prose '$prose': $a line(s) before, $b after"
done
rc=0; bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 --dry-run --skip-central "$WORK/dry" > "$WORK/dry2.txt" 2>&1 || rc=$?
[ "$rc" -eq 0 ] && grep -q '^sweep: 0 line(s) rewritten' "$WORK/dry2.txt" && pass "second sweep rewrites nothing (idempotent)" \
  || { fail "second sweep: exit $rc"; grep '^sweep:' "$WORK/dry2.txt" | head -20 | sed 's/^/    /'; }
echo '      - uses: pvgiang/spring-tune@v0.4.10   # a future 0.4.10 is not 0.4.1' > "$WORK/dry/docs/seed-boundary.md"
out="$(bash "$WORK/dry/scripts/check-install-strings.sh" "$WORK/dry" 2>&1)" && pass "0.4.10 is not flagged as stale 0.4.1" || fail "0.4.10 flagged: $out"
echo '      - uses: pvgiang/spring-tune@v0.4.1' >> "$WORK/dry/docs/seed-boundary.md"
bash "$WORK/dry/scripts/check-install-strings.sh" "$WORK/dry" > /dev/null 2>&1 && fail "@v0.4.1 not flagged after the sweep" \
  || pass "@v0.4.1 is flagged after the sweep"

# ---------------------------------------------------------------------------------------------------------------
# 2b. Provenance (review of iteration 7): a historical line marked "sweep: keep" is neither rewritten nor flagged; a
#     sentence about which tag first shipped something is not a "the vX tag defaults to CLI" comment; a line that
#     pairs a jar name with a SHA-256 is not silently given the new jar name next to the old hash.
copy_tree "$WORK/prov"
kept='| 0.4.1 on Central | open: `springtune-check-cli-0.4.1.pom` returned 404 on 2026-11-09 | <!-- sweep: keep -->'
kept_yml='        version: "0.4.1"   # sweep: keep (what the 2026-11-09 run used)'
history='(your own build) is not verified. The verification ships from the `v0.4.1` tag on; the `v0.4.0` tag'"'"'s Action'
lag='    version: "0.4.1"   # pass it: the v0.4.1 tag defaults to CLI 0.4.0'
printf '%s\n%s\n%s\n%s\n' "$kept" "$kept_yml" "$history" "$lag" > "$WORK/prov/docs/seed-provenance.md"
rc=0; bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 --dry-run --skip-central "$WORK/prov" > "$WORK/prov.txt" 2>&1 || rc=$?
[ "$rc" -eq 0 ] && pass "dry run with keep-marked lines exits 0" || { fail "dry run with keep-marked lines: exit $rc"; sed 's/^/    /' "$WORK/prov.txt"; }
grep -qxF -- "$kept" "$WORK/prov/docs/seed-provenance.md" && grep -qxF -- "$kept_yml" "$WORK/prov/docs/seed-provenance.md" \
  && pass "lines marked 'sweep: keep' are not rewritten" || fail "a keep-marked line was rewritten: $(head -2 "$WORK/prov/docs/seed-provenance.md")"
grep -qxF -- "$history" "$WORK/prov/docs/seed-provenance.md" && pass "'ships from the v0.4.1 tag on' is not taken for a lag comment" \
  || fail "history line rewritten: $(sed -n 3p "$WORK/prov/docs/seed-provenance.md")"
grep -qxF -- '    version: "0.4.2"   # pass it: the v0.4.2 tag defaults to CLI 0.4.1' "$WORK/prov/docs/seed-provenance.md" \
  && pass "a real lag comment is still swept" || fail "lag comment: $(sed -n 4p "$WORK/prov/docs/seed-provenance.md")"
copy_tree "$WORK/hash"
hash_line='echo "3f3726f752ae7605ee0687ac9a1f2158585928bc593cb90b95d45e4b140b599e  springtune-check-cli-0.4.1-all.jar" | sha256sum -c -'
echo "$hash_line" > "$WORK/hash/docs/seed-hash.md"
echo 'SpringTune 0.4.2: 1 module(s)' >> "$WORK/hash/docs/readme-runs/check-runs.txt"
: > "$WORK/hash/docs/readme-runs/build-plugins-0.4.2.txt"
sum_before="$(tree_sum "$WORK/hash")"
rc=0; bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 --skip-central "$WORK/hash" > "$WORK/hash.txt" 2>&1 || rc=$?
[ "$rc" -eq 1 ] && grep -q 'SHA-256' "$WORK/hash.txt" && pass "a jar name next to a SHA-256: the sweep refuses (exit 1)" \
  || { fail "jar name next to a SHA-256: exit $rc"; grep -i 'sha\|seed-hash' "$WORK/hash.txt" | sed 's/^/    /'; }
[ "$(tree_sum "$WORK/hash")" = "$sum_before" ] && pass "and changes nothing" || fail "the refused sweep changed files"
rc=0; bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 --dry-run --skip-central "$WORK/hash" > "$WORK/hash2.txt" 2>&1 || rc=$?
grep -q '::warning.*SHA-256' "$WORK/hash2.txt" && pass "dry run: warns about the SHA-256 line" || fail "dry run: no SHA-256 warning (exit $rc)"

# ---------------------------------------------------------------------------------------------------------------
# 3. Refusals. A stub curl prints the HTTP code given in FAKE_CODE.
mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\nprintf "%%s" "$FAKE_CODE"\n[ "$FAKE_CODE" != 000 ]\n' > "$WORK/bin/curl"
chmod +x "$WORK/bin/curl"
copy_tree "$WORK/real"
before="$(tree_sum "$WORK/real")"
refuse() {  # refuse CODE EXPECTED_MESSAGE
  local rc=0
  PATH="$WORK/bin:$PATH" FAKE_CODE="$1" bash "$HERE/sweep-install-version.sh" 0.4.1 0.4.2 "$WORK/real" > "$WORK/ref.txt" 2>&1 || rc=$?
  if [ "$rc" -eq 1 ] && grep -qF -- "$2" "$WORK/ref.txt" && [ "$(tree_sum "$WORK/real")" = "$before" ]; then
    pass "Central HTTP $1: exit 1, '$2', nothing changed"
  else
    fail "Central HTTP $1: exit $rc"; sed 's/^/    /' "$WORK/ref.txt"
  fi
}
refuse 404 "0.4.2 is not on Maven Central yet: press Publish on central.sonatype.com"
refuse 429 "could not check Central (HTTP 429"
refuse 503 "could not check Central (HTTP 503"
refuse 000 "could not check Central (HTTP 000"
refuse 200 "README outputs not re-captured with 0.4.2"
grep -q 'springtune-check-cli 0.4.2 is on Maven Central (HTTP 200)' "$WORK/ref.txt" && grep -q 'springtune-spring-boot-starter 0.4.2 is on Maven Central' "$WORK/ref.txt" \
  && pass "with HTTP 200 both poms are reported on Central before the capture check" || fail "HTTP 200 path did not check both poms"

usage() {  # usage DESCRIPTION ARGS...
  local what="$1" rc=0; shift
  bash "$HERE/sweep-install-version.sh" "$@" > "$WORK/use.txt" 2>&1 || rc=$?
  [ "$rc" -eq 2 ] && pass "usage error exits 2: $what" || { fail "$what: exit $rc"; sed 's/^/    /' "$WORK/use.txt"; }
}
usage "--dry-run without ROOT" 0.4.1 0.4.2 --dry-run --skip-central
usage "--dry-run on this repository" 0.4.1 0.4.2 --dry-run --skip-central "$ROOT"
usage "TO not above FROM" 0.4.2 0.4.1 --dry-run --skip-central "$WORK/real"
usage "not a release number" 0.4.1 latest --dry-run --skip-central "$WORK/real"
usage "unknown option" 0.4.1 0.4.2 --force "$WORK/real"
[ "$(tree_sum "$WORK/real")" = "$before" ] && pass "usage errors changed nothing" || fail "a usage error changed the copy"

# ---------------------------------------------------------------------------------------------------------------
# 4. The pom guard, on a minimal tree.
mini() {  # mini DIR POM_VERSION
  rm -rf "$1"; mkdir -p "$1/docs/releases" "$1/examples" "$1/scripts"
  cp "$ROOT/action.yml" "$1/"
  cp "$ROOT/scripts/check-install-strings.sh" "$1/scripts/"
  grep -m1 'pvgiang/spring-tune@v' "$ROOT/README.md" > "$1/README.md"
  for v in 0.4.0 0.4.1 0.4.2; do echo "notes $v" > "$1/docs/releases/v$v.md"; done
  printf '<project>\n  <artifactId>springtune-parent</artifactId>\n  <version>%s</version>\n</project>\n' "$2" > "$1/pom.xml"
}
guard() {  # guard WANT DESCRIPTION DIR [MESSAGE]
  local rc=0
  (cd "$3" && bash scripts/check-install-strings.sh "$3") > "$WORK/g.txt" 2>&1 || rc=$?
  if [ "$rc" -eq "$1" ] && { [ -z "${4:-}" ] || grep -qF -- "$4" "$WORK/g.txt"; }; then pass "pom guard: $2 (exit $rc)"
  else fail "pom guard: $2: exit $rc, expected $1"; sed 's/^/    /' "$WORK/g.txt"; fi
}
mini "$WORK/pom" 0.4.3-SNAPSHOT; guard 0 "0.4.3-SNAPSHOT above notes v0.4.2" "$WORK/pom"
mini "$WORK/pom" 0.4.2-SNAPSHOT; guard 1 "0.4.2-SNAPSHOT with notes v0.4.2 (no git): fails" "$WORK/pom" "not above 0.4.2"
mini "$WORK/pom" 0.4.2;          guard 1 "0.4.2 with notes v0.4.2: fails" "$WORK/pom" "not above 0.4.2"
mini "$WORK/pom" 0.4.1-SNAPSHOT; guard 1 "0.4.1-SNAPSHOT with notes v0.4.2: fails" "$WORK/pom" "not above 0.4.2"
mini "$WORK/pom" 1.0.0-SNAPSHOT; guard 0 "1.0.0-SNAPSHOT above notes v0.4.2" "$WORK/pom"
mini "$WORK/pom" 0.4.9-SNAPSHOT; echo "notes 0.4.10" > "$WORK/pom/docs/releases/v0.4.10.md"
guard 1 "notes v0.4.10 vs pom 0.4.9-SNAPSHOT: versions compare numerically" "$WORK/pom" "not above 0.4.10"
mini "$WORK/pom" 0.4.11-SNAPSHOT; echo "notes 0.4.10" > "$WORK/pom/docs/releases/v0.4.10.md"
guard 0 "notes v0.4.10 vs pom 0.4.11-SNAPSHOT" "$WORK/pom"
# The release commit: X.Y.Z-SNAPSHOT with vX.Y.Z notes, origin without and then with the tag.
mini "$WORK/git" 0.4.2-SNAPSHOT
git init -q --bare "$WORK/origin.git"
(
  cd "$WORK/git"
  git init -q
  git add -A
  git -c user.name=test -c user.email=test@example.invalid commit -q -m seed
  git remote add origin "$WORK/origin.git"
  git push -q origin HEAD:refs/heads/main 2> /dev/null
)
guard 0 "0.4.2-SNAPSHOT, notes v0.4.2, no v0.4.2 tag on origin: release pending" "$WORK/git" "not tagged on origin yet"
(cd "$WORK/git" && git push -q origin HEAD:refs/tags/v0.4.2 2> /dev/null)
guard 1 "0.4.2-SNAPSHOT, notes v0.4.2, v0.4.2 tagged on origin: fails" "$WORK/git" "v0.4.2 is released"
(cd "$WORK/git" && git remote set-url origin "$WORK/no-such-origin.git")
guard 1 "origin unreachable: fails and asks for a re-run, never a skip" "$WORK/git" "re-run"

if [ "$failures" -gt 0 ]; then echo "test-sweep: $failures failure(s)"; exit 1; fi
echo "test-sweep: all passed"
