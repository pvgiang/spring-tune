#!/usr/bin/env bash
#   scripts/petclinic/evaluate.sh [runs-dir] > summary.tsv
# Classifies each poll by wall clock: evaluated = generatedAt between driver start + 360 s and the end of the
# driver's schedule (start + 450 s); after-load = later.
R="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/docs/benchmarks/petclinic/2026-11-09-runs}"
printf 'scenario\trun\tseed\tpolls_evaluated\tpolls_with_rec\trecs_in_evaluated_polls\tpolls_after_load\trecs_after_load\n'
for s in s1 s2 s3 s4; do for n in 1 2 3; do
  d=$R/$s/run-$n
  jq -rs --slurpfile drv $d/driver-summary.json --arg s "$s" --arg n "$n" '
    def ts: sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601;
    ($drv[0].started_at | ts) as $st | ($st + $drv[0].duration_seconds) as $end
    | [.[] | select(.generatedAt != null) | {at: (.generatedAt | ts), recs: [.recommendations[] | "\(.ruleId) \(.action) \(.currentValue)->\(.recommendedValue)"]}] as $p
    | ($p | map(select(.at >= $st + 360 and .at <= $end))) as $ev
    | ($p | map(select(.at > $end))) as $after
    | [$s, $n, ($drv[0].seed | tostring), ($ev | length | tostring),
       ($ev | map(select(.recs | length > 0)) | length | tostring),
       ($ev | map(.recs[]) | unique | join("; ")),
       ($after | length | tostring), ($after | map(.recs[]) | unique | join("; "))] | @tsv' $d/polls.jsonl
done; done
