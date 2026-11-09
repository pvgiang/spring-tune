#!/usr/bin/env bash
# Fails when user-facing docs show a stale install string, so a copy-paste never installs an old SpringTune.
#
#   scripts/check-install-strings.sh [REPO_ROOT]
#
# Scans README.md, action.yml, docs/** and examples/**, except the historical records docs/releases/ (release
# notes), docs/delivery/ (briefs), docs/corpus/ (raw corpus results, which name the version they ran) and
# docs/adoption/ (measurement logs, which name each release's assets and download counts). Also
# checks that the Action's default CLI version is the one the README's Action snippet pins.
# Run by the "Docs: install strings" job in .github/workflows/ci.yml.
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT"

STALE=(
  '@v0.1.0'
  '@v0.3.0'
  'springtune-cli-0.1.0'
  'springtune-cli-0.3.0'
  '0.3.0</version>'
  'version "0.3.0"'
  'springtune-maven-repo-0.'
)

files=()
while IFS= read -r f; do files+=("$f"); done < <(
  { echo README.md; echo action.yml; find docs examples -type f \( -name '*.md' -o -name '*.yml' -o -name '*.yaml' \
      -o -name '*.xml' -o -name '*.kts' -o -name '*.gradle' -o -name '*.properties' \); } \
    | grep -Ev '^docs/(releases|delivery|corpus|adoption)/' | sort -u
)

status=0
for pattern in "${STALE[@]}"; do
  if hits="$(grep -nF -- "$pattern" "${files[@]}")"; then
    while IFS= read -r hit; do
      echo "::error file=${hit%%:*}::stale install string '$pattern': $hit"
    done <<< "$hits"
    status=1
  fi
done

default="$(awk '/^  version:/ {v=1} v && /default:/ {gsub(/[" ]|default:/, ""); print; exit}' action.yml)"
if [ -z "$default" ]; then
  echo "::error file=action.yml::could not read the version input's default"
  status=1
elif ! grep -qF "pvgiang/spring-tune@v$default" README.md; then
  echo "::error file=README.md::the Action defaults to CLI $default, but README.md has no 'pvgiang/spring-tune@v$default'"
  status=1
fi

[ "$status" -eq 0 ] && echo "Install strings OK (${#files[@]} files; Action default CLI $default)"
exit "$status"
