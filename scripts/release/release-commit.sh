#!/usr/bin/env bash
# Makes the tag vVERSION point to a tree whose action.yml defaults to CLI VERSION (iteration-7 brief row 2).
#
#   scripts/release/release-commit.sh VERSION BUILT_SHA dispatch|push
#
# Run by .github/workflows/release.yml after the build and tests, from the repository checkout (origin must accept a
# tag push: the job's token with contents: write). Writes `sha=<the tag's commit>` to $GITHUB_OUTPUT when set.
#
#   dispatch  The tag must not exist yet. In a separate worktree at BUILT_SHA, stamp-action-version.sh VERSION; if
#             that changes action.yml, commit it as github-actions[bot] (one file, parent BUILT_SHA), else use
#             BUILT_SHA itself. Check the commit (parent, `git diff --name-only BUILT_SHA <commit>` == action.yml,
#             at most the version input's 2 lines, default == VERSION), tag it (annotated) and push only
#             refs/tags/vVERSION. Nothing is pushed to a branch. If the tag already exists (a re-run after the tag
#             push), it is reused only if it passes the same checks.
#   push      The tag was pushed by a person and cannot be re-pointed: exit 1 unless action.yml at BUILT_SHA already
#             defaults to VERSION.
# In both modes it finally checks that origin's tag resolves to the checked commit. Tested by
# scripts/release/test-release-commit.sh.
set -euo pipefail

[ "$#" -eq 3 ] || { echo "usage: release-commit.sh VERSION BUILT_SHA dispatch|push" >&2; exit 2; }
VERSION="$1"; BUILT="$2"; MODE="$3"; TAG="v$VERSION"
HERE="$(cd "$(dirname "$0")" && pwd)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "::error::invalid version '$VERSION'"; exit 2; }
BUILT="$(git rev-parse --verify "$BUILT^{commit}")"
BOT=(-c "user.name=github-actions[bot]" -c "user.email=41898282+github-actions[bot]@users.noreply.github.com")

git cat-file -e "$BUILT:docs/releases/$TAG.md" 2> /dev/null \
  || { echo "::error::docs/releases/$TAG.md is missing in $BUILT"; exit 1; }

default_of() {  # default_of REV: the version input's default in REV:action.yml
  git show "$1:action.yml" | awk '/^inputs:/ {i=1; next} i && /^[^ ]/ {i=0} i && /^  version:/ {v=1; next}
    v && /^  [^ ]/ {v=0} v && /^    default:/ {gsub(/[" '"'"']|default:/, ""); print; exit}'
}
check_release_commit() {  # check_release_commit SHA
  local sha="$1"
  if [ "$sha" != "$BUILT" ]; then
    [ "$(git rev-parse "$sha^")" = "$BUILT" ] \
      || { echo "::error::release commit $sha is not a child of the built commit $BUILT"; return 1; }
    [ "$(git rev-list --count "$BUILT..$sha")" = 1 ] \
      || { echo "::error::release commit $sha is more than one commit on top of $BUILT"; return 1; }
    [ "$(git diff --name-only "$BUILT" "$sha")" = "action.yml" ] \
      || { echo "::error::release commit $sha changes more than action.yml: $(git diff --name-only "$BUILT" "$sha" | tr '\n' ' ')"; return 1; }
    [ "$(git diff -U0 "$BUILT" "$sha" -- action.yml | grep -c '^[-+][^-+]' || true)" -le 4 ] \
      || { echo "::error::release commit $sha changes more than the version input's lines in action.yml"; return 1; }
  fi
  [ "$(default_of "$sha")" = "$VERSION" ] \
    || { echo "::error::action.yml at $sha defaults to CLI '$(default_of "$sha")', not $VERSION"; return 1; }
}
output() { if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "sha=$1" >> "$GITHUB_OUTPUT"; fi; }

case "$MODE" in
  push)
    if [ "$(default_of "$BUILT")" != "$VERSION" ]; then
      echo "::error::The pushed tag $TAG has action.yml defaulting to CLI '$(default_of "$BUILT")', not $VERSION, so @$TAG without version: would run the wrong CLI. Delete the tag and release through Actions > Release > Run workflow (it stamps the tag), or tag a commit whose action.yml already defaults to $VERSION (scripts/release/stamp-action-version.sh $VERSION). See docs/releasing.md."
      exit 1
    fi
    sha="$BUILT"
    echo "Pushed tag $TAG: action.yml already defaults to CLI $VERSION"
    ;;
  dispatch)
    rc=0; git ls-remote --exit-code --tags origin "refs/tags/$TAG" > /dev/null || rc=$?
    if [ "$rc" -eq 0 ]; then
      git fetch -q --no-tags origin "+refs/tags/$TAG:refs/tags/$TAG"
      sha="$(git rev-parse "$TAG^{commit}")"
      check_release_commit "$sha" || { echo "::error::tag $TAG already exists on origin and is not a release commit of $BUILT"; exit 1; }
      echo "Tag $TAG exists and is the release commit $sha of $BUILT: reusing it"
    elif [ "$rc" -eq 2 ]; then
      dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/release-commit.XXXXXX")"
      rmdir "$dir"
      git worktree add -q --detach "$dir" "$BUILT"
      bash "$HERE/stamp-action-version.sh" "$VERSION" "$dir/action.yml"
      if git -C "$dir" diff --quiet; then
        sha="$BUILT"   # the built commit already defaults to VERSION: tag it as it is
      else
        git -C "$dir" add action.yml
        git -C "$dir" "${BOT[@]}" commit -q -m "Release $VERSION: action.yml defaults to CLI $VERSION" \
          -m "Release commit made by the Release workflow${GITHUB_RUN_ID:+ (run $GITHUB_RUN_ID)} on top of $BUILT. Reachable from the tag $TAG only."
        sha="$(git -C "$dir" rev-parse HEAD)"
      fi
      git worktree remove --force "$dir"
      check_release_commit "$sha"
      git "${BOT[@]}" tag -a "$TAG" -m "SpringTune $VERSION" "$sha"
      git push -q origin "refs/tags/$TAG"
    else
      echo "::error::could not list origin's tags (git ls-remote exit $rc)"
      exit 1
    fi
    ;;
  *) echo "usage: release-commit.sh VERSION BUILT_SHA dispatch|push" >&2; exit 2 ;;
esac

# What origin has under the tag is what was checked.
remote="$(git ls-remote origin "refs/tags/$TAG^{}" | cut -f1)"
[ -n "$remote" ] || remote="$(git ls-remote origin "refs/tags/$TAG" | cut -f1)"
[ "$remote" = "$sha" ] || { echo "::error::origin's $TAG resolves to '$remote', expected $sha"; exit 1; }
output "$sha"
echo "$TAG -> $sha (built commit $BUILT); action.yml defaults to CLI $VERSION"
