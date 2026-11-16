#!/usr/bin/env bash
# Validates a gates snapshot before the store job of .github/workflows/gates-collect.yml pushes it to gates-data.
#
#   scripts/gates/validate-snapshot.sh snapshot.json
#
# The snapshot crosses a trust boundary (an artifact written by the job that holds the PAT), so only a small JSON
# object with exactly the shape collect.sh writes is accepted. Exit 0 and one "snapshot ok" line, or exit 1 with the
# reason. The workflow runs this file, so a local run is the same check (docs/adoption/gates.md#collector).
set -euo pipefail

SNAPSHOT="${1:?usage: validate-snapshot.sh snapshot.json}"
err() { echo "::error::$*"; exit 1; }

test -f "$SNAPSHOT" || err "snapshot.json missing ($SNAPSHOT)"
size="$(stat -c %s "$SNAPSHOT")"
[ "$size" -lt 1048576 ] || err "snapshot is $size bytes (limit 1 MB)"
# --slurp: the file must hold exactly one JSON value. Without it, jq -e takes its exit status from the last value
# only, so arbitrary JSON followed by a valid snapshot would pass and be appended to snapshots.jsonl.
jq -e -s '
  ["schema","collected_at","repo","auth","repository","stargazers","issues","releases",
   "action_users","traffic","not_collected"] as $k
  | length == 1 and (.[0]
  | type == "object"
  and (keys - $k | length == 0)
  and ($k - keys | length == 0)
  and .schema == 1
  and (.collected_at | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
  and (.repo | type == "string")
  and (.auth | type == "object"
       and (keys == ["action_users","issues","releases","repository","stargazers","traffic"])
       and ([.[]] | all(type == "string")))
  and ([.repository, .stargazers, .issues, .releases, .action_users, .traffic] | all(type == "object"))
  and (.releases.available == false
       or (.releases.releases | type == "array"
           and all(.[]; has("own_ci_runs_since") and has("downloads_per_own_ci_run")
                        and (.own_ci_runs_since | type == "number" or type == "null")
                        and (.downloads_per_own_ci_run | type == "number" or type == "null"))))
  and (.not_collected | type == "array"))
' "$SNAPSHOT" >/dev/null 2>&1 || err "snapshot.json does not have the shape collect.sh writes"
echo "snapshot ok: $size bytes, collected_at $(jq -r .collected_at "$SNAPSHOT")"
