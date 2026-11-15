#!/usr/bin/env bash
# Fails when user-facing docs show a stale install string, so a copy-paste never installs an old SpringTune.
#
#   scripts/check-install-strings.sh [REPO_ROOT]
#   scripts/check-install-strings.sh --list-files [REPO_ROOT]    # the scanned files, one per line (relative)
#   scripts/check-install-strings.sh --list-templates            # the stale-string templates, one per line
#
# Scans README.md, action.yml, docs/** and examples/**, except the historical records docs/releases/ (release
# notes), docs/delivery/ (briefs), docs/corpus/ (raw corpus results, which name the version they ran),
# docs/adoption/ (measurement logs, which name each release's assets and download counts) and docs/readme-runs/
# (the raw captures behind the README's outputs, which name the jar that produced them).
#
# Checks:
#   1. No install string of a stale version: every version in STALE_VERSIONS × every template in TEMPLATES.
#      Templates match install strings only (Action refs, `version:` inputs, jar names, Central paths, coordinates,
#      <version> elements, release URLs). Prose that names the version a measurement was made with ("the 0.4.0
#      CLI", the 2026-11-09-0.4.0 re-run) matches none of them and stays.
#   2. The Action's default CLI version (action.yml on this tree) is the one the README's Action snippet pins.
#   3. The root pom's version is above the highest release with notes in docs/releases/ (skeptic C9, iteration 7):
#      after vX.Y.Z is released, main must not say X.Y.Z-SNAPSHOT or lower. While vX.Y.Z's notes exist but the tag
#      does not (the release commit, before the Release workflow runs), X.Y.Z-SNAPSHOT is accepted; that needs the
#      tag list of `origin`, so in a checkout without one the stricter rule applies.
#
# scripts/release/sweep-install-version.sh appends to STALE_VERSIONS (it reads the files and templates from
# here, so both agree). Run by the "Docs: install strings" job in .github/workflows/ci.yml; tested by
# scripts/release/test-sweep.sh.
set -euo pipefail

# Releases whose install strings are stale. 0.4.1 is current.
STALE_VERSIONS=(0.1.0 0.3.0 0.4.0)

# Extended regular expressions; {V} is the version with its dots escaped. A template that ends with {V} also
# requires that no digit follows (0.4.1 does not flag 0.4.10).
TEMPLATES=(
  '@v{V}'
  'springtune-cli-{V}'
  'springtune-check-cli-{V}'
  'check-cli/{V}'
  'check-cli:{V}'
  'winkytek/springtune/[a-z.-]+/{V}/'
  '{V}</version>'
  '{V}</springtune\.version>'
  'version "{V}"'
  'version: "{V}"'
  "version '{V}'"
  'releases/download/v{V}/'
  'releases/tag/v{V}'
  'springtune-maven-plugin:{V}'
  'springtune-gradle-plugin:{V}'
  'springtune-spring-boot-starter:{V}'
  'springtune-limiter:{V}'
)
# A line that records the past (a measurement, a provenance note, what a dated check returned) may carry an old
# install string if it is marked with this text, e.g. `<!-- sweep: keep -->` at the end of a Markdown line or
# `# sweep: keep` in YAML. The sweep never rewrites such a line either. Use it for history only, never for a snippet.
KEEP='sweep: keep'
# Fixed strings, not per version: the Maven repository zip is not an install path any more (Central is).
FIXED=('springtune-maven-repo-0.')

mode=check
case "${1:-}" in
  --list-files) mode=files; shift ;;
  --list-templates) printf '%s\n' "${TEMPLATES[@]}"; exit 0 ;;
  --list-stale-versions) printf '%s\n' "${STALE_VERSIONS[@]}"; exit 0 ;;
  -*) echo "usage: check-install-strings.sh [--list-files|--list-templates|--list-stale-versions] [REPO_ROOT]" >&2; exit 2 ;;
esac
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT"

files=()
while IFS= read -r f; do files+=("$f"); done < <(
  { echo README.md; echo action.yml; find docs examples -type f \( -name '*.md' -o -name '*.yml' -o -name '*.yaml' \
      -o -name '*.xml' -o -name '*.kts' -o -name '*.gradle' -o -name '*.properties' \); } \
    | grep -Ev '^docs/(releases|delivery|corpus|adoption|readme-runs)/' | sort -u
)
if [ "$mode" = files ]; then printf '%s\n' "${files[@]}"; exit 0; fi

# instance TEMPLATE VERSION -> the ERE for that version
instance() {
  local t="$1" v="${2//./\\.}"
  case "$t" in *'{V}') t="${t%\{V\}}$v([^0-9]|\$)" ;; esac
  printf '%s' "${t//\{V\}/$v}"
}

status=0
report() {  # report LABEL HITS
  while IFS= read -r hit; do
    echo "::error file=${hit%%:*}::stale install string '$1': $hit"
  done <<< "$2"
  status=1
}
for v in "${STALE_VERSIONS[@]}"; do
  for t in "${TEMPLATES[@]}"; do
    if hits="$(grep -nE -- "$(instance "$t" "$v")" "${files[@]}" | grep -vF -- "$KEEP")"; then
      label="${t//\{V\}/$v}"
      report "${label//\\/}" "$hits"
    fi
  done
done
for pattern in "${FIXED[@]}"; do
  if hits="$(grep -nF -- "$pattern" "${files[@]}" | grep -vF -- "$KEEP")"; then report "$pattern" "$hits"; fi
done

# 2. The Action's default (the `version` input under `inputs:`) is the README's pin.
default="$(awk '/^inputs:/ {i=1; next} i && /^[^ ]/ {i=0} i && /^  version:/ {v=1; next} v && /^  [^ ]/ {v=0}
  v && /^    default:/ {gsub(/[" '"'"']|default:/, ""); print; exit}' action.yml)"
if [ -z "$default" ]; then
  echo "::error file=action.yml::could not read the version input's default"
  status=1
elif ! grep -qF "pvgiang/spring-tune@v$default" README.md; then
  echo "::error file=README.md::the Action defaults to CLI $default, but README.md has no 'pvgiang/spring-tune@v$default'"
  status=1
fi

# 3. The pom is above the latest release with notes.
version_gt() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }
pom="$(awk '/<artifactId>springtune-parent<\/artifactId>/ {p=1; next} p && /<version>/ {
  gsub(/.*<version>|<\/version>.*/, ""); print; exit}' pom.xml 2> /dev/null || true)"
latest="$(find docs/releases -maxdepth 1 -name 'v*.md' 2> /dev/null | sed -n 's:.*/v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)\.md$:\1:p' \
  | sort -V | tail -1)"
if [ -z "$pom" ]; then
  echo "::error file=pom.xml::could not read the root pom's version (springtune-parent)"
  status=1
elif [ -n "$latest" ]; then
  base="${pom%%-*}"
  if version_gt "$base" "$latest"; then
    :
  elif [ "$base" = "$latest" ] && [ "$pom" != "$base" ] \
      && [ "$(git rev-parse --show-toplevel 2> /dev/null || true)" = "$(pwd -P)" ] \
      && git remote get-url origin > /dev/null 2>&1; then
    # X.Y.Z-SNAPSHOT with vX.Y.Z notes: the release commit before the release, or main after it.
    tag_rc=0
    git ls-remote --exit-code --tags origin "refs/tags/v$latest" > /dev/null 2>&1 || tag_rc=$?
    if [ "$tag_rc" -eq 2 ]; then
      echo "::notice file=pom.xml::docs/releases/v$latest.md exists and pom.xml is $pom: v$latest is not tagged on origin yet (release pending)"
    elif [ "$tag_rc" -eq 0 ]; then
      echo "::error file=pom.xml::v$latest is released (tag on origin, notes docs/releases/v$latest.md), but pom.xml still says $pom: bump to the next -SNAPSHOT"
      status=1
    else
      echo "::error file=pom.xml::pom.xml is $pom and docs/releases/v$latest.md exists; could not list origin's tags to tell whether v$latest is released (git ls-remote exit $tag_rc): re-run"
      status=1
    fi
  else
    echo "::error file=pom.xml::pom.xml is $pom, not above $latest, the latest release with notes (docs/releases/v$latest.md): bump to the next -SNAPSHOT"
    status=1
  fi
fi

[ "$status" -eq 0 ] && echo "Install strings OK (${#files[@]} files; Action default CLI $default; pom $pom, latest release notes v$latest)"
exit "$status"
