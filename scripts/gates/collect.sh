#!/usr/bin/env bash
# Collects the public and owner-visible adoption signals of docs/adoption/gates.md into one JSON snapshot.
#
#   scripts/gates/collect.sh [output.json]          # default: stdout
#
# Needs gh (authenticated: GH_TOKEN, or `gh auth login`) and jq. Environment:
#   REPO         owner/name to collect (default pvgiang/spring-tune)
#   GATES_TOKEN  optional fine-grained PAT with "Administration: read" on REPO. Only used for the traffic endpoints
#                (views, clones, referrers, paths), which GitHub keeps for 14 days and only shows to admins.
#                Without it the snapshot says "traffic": {"available": false, ...}.
#   MAX_STARGAZER_PROFILES  stargazers whose profile (company) is looked up, newest first (default 300)
#
# Every section is collected independently: a section that fails is recorded as {"available": false, "reason": ...}
# and the others are still written. The exit code is 0 unless gh or jq is missing or the repository is unreadable.
set -euo pipefail

REPO="${REPO:-pvgiang/spring-tune}"
OUT="${1:-}"
MAX_STARGAZER_PROFILES="${MAX_STARGAZER_PROFILES:-300}"
GATE_LABELS=(rule-request false-positive rec-feedback paid-signal
  outcome-applied outcome-edited outcome-accepted outcome-rejected pilot-paid pilot-free)

command -v gh >/dev/null || { echo "collect.sh: gh not found" >&2; exit 2; }
command -v jq >/dev/null || { echo "collect.sh: jq not found" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Runs "$@" (which prints JSON); on failure prints {"available": false, "reason": <last stderr line>}.
# The functions run inside `if`, where set -e does not apply, so each one returns 1 explicitly on a failed call.
section() {
  local name="$1"; shift
  if "$@" >"$WORK/$name.json" 2>"$WORK/$name.err"; then
    cat "$WORK/$name.json"
  else
    echo "collect.sh: $name unavailable: $(tail -n 1 "$WORK/$name.err")" >&2
    jq -n --arg r "$(tail -n 1 "$WORK/$name.err")" '{available: false, reason: $r}'
  fi
}

repo_info() {
  gh api "repos/$REPO" --jq '{
    full_name, stars: .stargazers_count, forks: .forks_count, watchers: .subscribers_count,
    open_issues_and_prs: .open_issues_count, description, homepage, topics, default_branch, pushed_at }'
}

stargazers() {
  # The star+json media type adds starred_at to each entry.
  gh api --paginate -H "Accept: application/vnd.github.star+json" "repos/$REPO/stargazers?per_page=100" \
    --jq '.[] | {login: .user.login, starred_at}' | jq -s 'sort_by(.starred_at) | reverse' >"$WORK/stars.json" || return 1
  local i=0 login company
  : >"$WORK/companies.jsonl"
  for login in $(jq -r '.[].login' "$WORK/stars.json"); do
    [ "$i" -lt "$MAX_STARGAZER_PROFILES" ] || break
    i=$((i + 1))
    company="$(gh api "users/$login" --jq '.company // ""' 2>/dev/null || true)"
    jq -nc --arg l "$login" --arg c "$company" '{($l): (if $c == "" then null else $c end)}' >>"$WORK/companies.jsonl"
  done
  jq --slurpfile c "$WORK/companies.jsonl" --argjson max "$MAX_STARGAZER_PROFILES" '
    ($c | add // {}) as $co
    | { count: length,
        with_company: ([.[] | select($co[.login] != null)] | length),
        profiles_looked_up: ([length, $max] | min),
        stargazers: [.[] | . + {company: $co[.login]}] }' "$WORK/stars.json"
}

issues_by_label() {
  # /issues also returns pull requests; drop them.
  gh api --paginate "repos/$REPO/issues?state=all&per_page=100" --jq '.[] | select(.pull_request | not) | {
      number, title, state, created_at, closed_at, author: .user.login, author_association,
      labels: [.labels[].name] }' | jq -s '.' >"$WORK/issues-raw.json" || return 1
  local labels_json
  labels_json="$(printf '%s\n' "${GATE_LABELS[@]}" | jq -R . | jq -s .)"
  jq --argjson labels "$labels_json" '
    { total_issues: length,
      by_label: (reduce $labels[] as $l ({}; . + { ($l): (
        [ .[] | select(.labels | index($l)) ] as $m
        | { count: ($m | length),
            from_outside: ([ $m[] | select(.author_association | IN("OWNER", "MEMBER", "COLLABORATOR") | not) ] | length),
            issues: $m } ) })) }' "$WORK/issues-raw.json"
}

releases() {
  gh api --paginate "repos/$REPO/releases?per_page=100" --jq '.[] | {
      tag: .tag_name, published_at, draft, prerelease,
      assets: [.assets[] | {name, download_count}],
      downloads: ([.assets[].download_count] | add // 0) }' \
    | jq -s '{ total_downloads: ([.[].downloads] | add // 0), releases: . }'
}

action_users() {
  # Public workflows that reference the Action (`uses: pvgiang/spring-tune@...`). Code search needs a user token
  # (gh login or a PAT); the default GITHUB_TOKEN of a workflow may be refused or see only this repository.
  local q="${REPO}@ path:.github/workflows"
  gh api -X GET search/code -f q="$q" -f per_page=100 --paginate \
    --jq '.items[] | {repo: .repository.full_name, path, html_url}' \
    | jq -s --arg self "$REPO" --arg q "$q" '
      [ .[] | select(.repo != $self) ] as $hits
      | { query: $q, repositories: ($hits | map(.repo) | unique), count: ($hits | map(.repo) | unique | length),
          files: $hits,
          note: "public repositories only; GitHub code search indexes default branches and may lag by days" }'
}

traffic() {
  if [ -z "${GATES_TOKEN:-}" ]; then
    jq -n '{available: false, reason: "traffic: unavailable (no GATES_TOKEN)",
            note: "views, clones, referrers and paths are kept by GitHub for 14 days only"}'
    return 0
  fi
  local t="$GATES_TOKEN"
  GH_TOKEN="$t" gh api "repos/$REPO/traffic/views?per=day" >"$WORK/views.json" || return 1
  GH_TOKEN="$t" gh api "repos/$REPO/traffic/clones?per=day" >"$WORK/clones.json" || return 1
  GH_TOKEN="$t" gh api "repos/$REPO/traffic/popular/referrers" >"$WORK/referrers.json" || return 1
  GH_TOKEN="$t" gh api "repos/$REPO/traffic/popular/paths" >"$WORK/paths.json" || return 1
  jq -n --slurpfile v "$WORK/views.json" --slurpfile c "$WORK/clones.json" \
        --slurpfile r "$WORK/referrers.json" --slurpfile p "$WORK/paths.json" \
    '{available: true, window_days: 14, views: $v[0], clones: $c[0], referrers: $r[0], paths: $p[0]}'
}

gh api "repos/$REPO" --jq .full_name >/dev/null   # fail fast if the repository cannot be read at all

if [ -n "${GATES_TOKEN:-}" ]; then auth="GATES_TOKEN for traffic; gh credentials for the rest"; else auth="gh credentials (no GATES_TOKEN)"; fi

snapshot="$(jq -n \
  --arg collected_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg repo "$REPO" \
  --arg auth "$auth" \
  --argjson repository "$(section repository repo_info)" \
  --argjson stargazers "$(section stargazers stargazers)" \
  --argjson issues "$(section issues issues_by_label)" \
  --argjson releases "$(section releases releases)" \
  --argjson action_users "$(section action_users action_users)" \
  --argjson traffic "$(section traffic traffic)" \
  '{ schema: 1, collected_at: $collected_at, repo: $repo, auth: $auth,
     repository: $repository, stargazers: $stargazers, issues: $issues, releases: $releases,
     action_users: $action_users, traffic: $traffic,
     not_collected: [
       "Maven Central downloads: owner-only on central.sonatype.com, copied by hand monthly (gates.md)",
       "Gradle Plugin Portal downloads: no public counter" ] }')"

if [ -n "$OUT" ]; then
  printf '%s\n' "$snapshot" >"$OUT"
  echo "collect.sh: wrote $OUT" >&2
else
  printf '%s\n' "$snapshot"
fi
