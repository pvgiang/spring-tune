#!/usr/bin/env bash
# Runs every agent-checkable item of docs/launch/checklist.md and prints one line per item:
#   PASS        checked, holds
#   FAIL        checked, does not hold (the line says what to do)
#   NO-CHECK    could not be checked (Maven Central 429/5xx, a network error): re-run later. Never a pass.
# Exit 0 only if every item is PASS. HUMAN items of the checklist are listed at the end, not checked.
#
#   scripts/launch/verify-checklist.sh
#
# Needs gh (authenticated), curl, jq, sha256sum. Environment: REPO (default pvgiang/spring-tune).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REPO="${REPO:-pvgiang/spring-tune}"
POST="$ROOT/docs/launch/launch-post.md"
VARIANTS="$ROOT/docs/launch/variants.md"
CENTRAL="${CENTRAL_BASE:-https://repo1.maven.org/maven2}/io/github/winkytek/springtune"   # CENTRAL_BASE: tests only
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0; nocheck=0
result() { # status id text
  printf '%-8s %-4s %s\n' "$1" "$2" "$3"
  case "$1" in PASS) pass=$((pass + 1)) ;; FAIL) fail=$((fail + 1)) ;; *) nocheck=$((nocheck + 1)) ;; esac
}
http_code() { # curl prints 000 itself when no response came back
  local c; c="$(curl -sS -o /dev/null -w '%{http_code}' --proto '=https' --max-time 30 "$@" 2>/dev/null)"; echo "${c:-000}"
}
# Central: 200 pass, 404 fail with $2, anything else NO-CHECK (rate limit or outage: re-run, never pass).
central() { # id url what
  local code; code="$(http_code -I --retry 2 "$2")"
  case "$code" in
    200) result PASS "$1" "$3: HTTP 200 ($2)" ;;
    404) result FAIL "$1" "$3: HTTP 404, not on Maven Central ($2)" ;;
    *)   result NO-CHECK "$1" "$3: could not check, re-run (HTTP $code for $2)" ;;
  esac
}

# The install version is the one in the post's CLI download line.
V="$(sed -n 's:.*springtune-check-cli/\([0-9][0-9.]*\)/springtune-check-cli-.*-all\.jar.*:\1:p' "$POST" | sort -u)"
if [ "$(printf '%s\n' "$V" | grep -c .)" -ne 1 ]; then
  result FAIL L0 "the post should name exactly one CLI version on Central, found '$(echo $V)'"
  V=0.0.0
fi

# L1 (conditional): a newer release on Central means the install lines are swept first.
NEXT="$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null | sed 's/^v//')"
if [ -z "$NEXT" ]; then
  result NO-CHECK L1 "latest GitHub release: could not read it, re-run"
elif [ "$NEXT" = "$V" ]; then
  result PASS L1 "the post installs $V, the latest GitHub release"
else
  code="$(http_code -I --retry 2 "$CENTRAL/springtune-check-cli/$NEXT/springtune-check-cli-$NEXT.pom")"
  case "$code" in
    404) result PASS L1 "$NEXT is released on GitHub but not on Central (HTTP 404): the post stays on $V" ;;
    200) result FAIL L1 "$NEXT is on Central: run scripts/release/sweep-install-version.sh $V $NEXT first, then re-run" ;;
    *)   result NO-CHECK L1 "is $NEXT on Central? could not check, re-run (HTTP $code)" ;;
  esac
fi

# L2: repository public.
vis="$(gh api "repos/$REPO" --jq .visibility 2>/dev/null)"
if [ "$vis" = public ]; then result PASS L2 "repository $REPO is public"
elif [ -z "$vis" ]; then result NO-CHECK L2 "repository visibility: could not read, re-run"
else result FAIL L2 "repository $REPO is '$vis': make it public (Settings, General, Danger Zone)"; fi

# L3: description and topics set.
desc_topics="$(gh api "repos/$REPO" --jq '[(.description // "" | length), (.topics | length)] | @tsv' 2>/dev/null)"
if [ -z "$desc_topics" ]; then result NO-CHECK L3 "description/topics: could not read, re-run"
else
  read -r dl tn <<<"$desc_topics"
  if [ "$dl" -gt 0 ] && [ "$tn" -gt 0 ]; then result PASS L3 "description set ($dl chars), $tn topics"
  else result FAIL L3 "description length $dl, topics $tn: set both (repository About, gear icon)"; fi
fi

# L4-L6: every coordinate the post installs resolves on Central.
central L4 "$CENTRAL/springtune-check-cli/$V/springtune-check-cli-$V-all.jar" "CLI jar $V"
grep -q "<artifactId>springtune-maven-plugin</artifactId>" "$POST" \
  && central L5 "$CENTRAL/springtune-maven-plugin/$V/springtune-maven-plugin-$V.pom" "Maven plugin $V" \
  || result FAIL L5 "the post has no springtune-maven-plugin snippet"
grep -q 'id("io.github.winkytek.springtune") version' "$POST" \
  && central L6 "$CENTRAL/io.github.winkytek.springtune.gradle.plugin/$V/io.github.winkytek.springtune.gradle.plugin-$V.pom" "Gradle plugin marker $V" \
  || result FAIL L6 "the post has no Gradle plugin snippet"

# L7: the SHA-256 in the post's CLI snippet = the release's SHA256SUMS = Central's .sha256.
post_sha="$(sed -n 's/.*echo "\([0-9a-f]\{64\}\)  springtune-check-cli-.*/\1/p' "$POST" | sort -u)"
sums_id="$(gh api "repos/$REPO/releases/tags/v$V" --jq '.assets[] | select(.name == "SHA256SUMS") | .id' 2>/dev/null)"
rel_sha=""
if [ -n "$sums_id" ] && gh api -H "Accept: application/octet-stream" "repos/$REPO/releases/assets/$sums_id" >"$WORK/SHA256SUMS" 2>/dev/null; then
  rel_sha="$(awk -v n="springtune-cli-$V.jar" '$2 == n || $2 == "*" n {print $1}' "$WORK/SHA256SUMS")"
fi
central_sha="$(curl -fsS --proto '=https' --max-time 30 "$CENTRAL/springtune-check-cli/$V/springtune-check-cli-$V-all.jar.sha256" 2>/dev/null | awk '{print $1}')"
if [ -z "$post_sha" ]; then result FAIL L7 "no SHA-256 found in the post's CLI snippet"
elif [ -z "$rel_sha" ] || [ -z "$central_sha" ]; then
  result NO-CHECK L7 "CLI SHA-256: could not read the release SHA256SUMS ('${rel_sha:-none}') or Central's .sha256 ('${central_sha:-none}'), re-run"
elif [ "$post_sha" = "$rel_sha" ] && [ "$post_sha" = "$central_sha" ]; then
  result PASS L7 "CLI SHA-256 in the post = v$V SHA256SUMS (springtune-cli-$V.jar) = Central .sha256 ($post_sha)"
else
  result FAIL L7 "CLI SHA-256 differs: post $post_sha, release $rel_sha, Central $central_sha"
fi

# L8: every github.com / raw.githubusercontent.com link in the post and the variants answers 200 anonymously
# (redirects followed; the fragment is not checked). issues/new links redirect anonymous users to the login page,
# so they are a HUMAN item (H3), not checked here.
grep -ohE 'https://(github\.com|raw\.githubusercontent\.com)/[^ )>"`]+' "$POST" "$VARIANTS" \
  | sed 's/[.,;:]$//' | sort -u >"$WORK/links"
bad=0; checked=0
while IFS= read -r url; do
  case "$url" in */issues/new*) continue ;; esac
  checked=$((checked + 1))
  code="$(http_code -L "${url%%#*}")"
  if [ "$code" != 200 ]; then echo "         L8   HTTP $code $url"; bad=$((bad + 1)); fi
done <"$WORK/links"
if [ "$checked" -eq 0 ]; then result FAIL L8 "no links found in the post and variants"
elif [ "$bad" -eq 0 ]; then result PASS L8 "$checked links in launch-post.md and variants.md answer HTTP 200"
else result FAIL L8 "$bad of $checked links do not answer HTTP 200 (listed above)"; fi

# L9: the README the post points at is the one-path-per-approach layout, on main.
readme="$(curl -fsS --proto '=https' --max-time 30 "https://raw.githubusercontent.com/$REPO/main/README.md" 2>/dev/null)"
if [ -z "$readme" ]; then result NO-CHECK L9 "README on main: could not fetch, re-run"
elif printf '%s\n' "$readme" | grep -q '^## Start here: pick one' \
  && [ "$(printf '%s\n' "$readme" | grep -cE '^## [1-5]\. ')" -eq 5 ]; then
  result PASS L9 "README on main has 'Start here: pick one' and five numbered paths"
else result FAIL L9 "README on main is not the one-path-per-approach layout the post links"; fi

# L10: launch content passes the claims check.
if out="$(bash "$ROOT/scripts/content/check-claims.sh" 2>&1)"; then result PASS L10 "$out"
else printf '%s\n' "$out" | sed 's/^/         /'; result FAIL L10 "scripts/content/check-claims.sh failed (above)"; fi

# L11: the latest CI run on main is green, including the jobs that run the post's snippets from Central and the demo.
run="$(gh api "repos/$REPO/actions/workflows/ci.yml/runs?branch=main&event=push&per_page=1" --jq '.workflow_runs[0] | [.id, .head_sha[0:7], .conclusion // "running"] | @tsv' 2>/dev/null)"
if [ -z "$run" ]; then result NO-CHECK L11 "CI on main: could not read, re-run"
else
  read -r rid rsha rconc <<<"$run"
  gh api "repos/$REPO/actions/runs/$rid/jobs?per_page=100" --jq '.jobs[] | [.conclusion // "running", .name] | @tsv' >"$WORK/jobs" 2>/dev/null
  need='^(Demo: |README snippets: |Action: checksum)'
  n_need="$(cut -f2 "$WORK/jobs" | grep -cE "$need")"
  n_ok="$(awk -F'\t' '$1 == "success"' "$WORK/jobs" | cut -f2 | grep -cE "$need")"
  if [ "$rconc" = success ] && [ "$n_need" -ge 6 ] && [ "$n_ok" -eq "$n_need" ]; then
    result PASS L11 "CI on main ($rsha, run $rid) success; demo, README snippets (Central) and published Action: $n_ok of $n_need green"
  else
    result FAIL L11 "CI on main ($rsha, run $rid) is '$rconc'; snippet/demo jobs green: $n_ok of $n_need (https://github.com/$REPO/actions/runs/$rid)"
  fi
fi

# L12: the labels and issue forms the post sends people to exist.
labels="$(gh api "repos/$REPO/labels?per_page=100" --jq '.[].name' 2>/dev/null)"
forms="$(gh api "repos/$REPO/contents/.github/ISSUE_TEMPLATE" --jq '.[].name' 2>/dev/null)"
if [ -z "$labels" ] || [ -z "$forms" ]; then result NO-CHECK L12 "labels/forms: could not read, re-run"
elif printf '%s\n' "$labels" | grep -qx false-positive && printf '%s\n' "$labels" | grep -qx rule-request \
  && printf '%s\n' "$forms" | grep -q false-positive && printf '%s\n' "$forms" | grep -q rule-request; then
  result PASS L12 "labels false-positive and rule-request exist; their issue forms are on the default branch"
else result FAIL L12 "a label or issue form is missing (see docs/adoption/gates.md#triage-labels)"; fi

# L13: the traffic collector can read traffic (launch-day referrers outlive GitHub's 14 days).
snap="$(gh api -H "Accept: application/vnd.github.raw" "repos/$REPO/contents/snapshots.jsonl?ref=gates-data" 2>/dev/null | tail -n 1)"
if [ -z "$snap" ]; then result NO-CHECK L13 "gates-data snapshot: could not read, re-run"
else
  at="$(jq -r .collected_at <<<"$snap")"; tr="$(jq -r .traffic.available <<<"$snap")"; st="$(jq -r .stargazers.available <<<"$snap")"
  # Stars keep their starred_at and can be recounted any day, so only traffic blocks; stargazers is shown.
  if [ "$tr" = true ]; then result PASS L13 "latest gates snapshot ($at): traffic collected (stargazers.available=$st)"
  else result FAIL L13 "latest gates snapshot ($at): traffic.available=$tr: renew GATES_TOKEN and run Gates collector (brief H4, H6)"; fi
fi

echo
echo "HUMAN (not checked here, see docs/launch/checklist.md): H1 claims trace recount, H2 apply wording, H3 links and"
echo "issue forms in an anonymous browser, H4 composites, H5 launch-log rows, H6 sign-off, H7 posting."
echo "verify-checklist: $pass PASS, $fail FAIL, $nocheck NO-CHECK"
[ "$fail" -eq 0 ] && [ "$nocheck" -eq 0 ]
