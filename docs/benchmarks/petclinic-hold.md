# PetClinic: a hold check for the S3 miss (pre-registered)

Follow-up of the [S3 miss](petclinic.md#s3-database-lock-pool-10-50-reqs-hikari-001-increase-fired-instead-of-dont-raise-miss)
of the [PetClinic benchmark](petclinic.md). Delivery brief: [iteration 7](../delivery/iteration-7-brief.md), row 7,
whose bar is binding here. It is not a performance benchmark: no latency, throughput or "faster" number is reported.

The order of this file's sections is the order of the work, and the git history proves it: step 0 (a finding on the
committed data) was committed first, step 1 (the pre-registration) was committed alone before any new run, and
results were added only after the runs.

## Step 0: what the committed 2026-11-09 data can say

**Finding 1: the committed polls contain only means, so no max or percentile check can be evaluated on them.** Every
field of the Hikari component and of every recommendation's evidence in the 48 committed `polls.jsonl` files
(12 runs), as printed by `python3 -I scripts/petclinic/hold-step0.py`:

- Hikari signals: `active`, `holdTimeMetrics`, `idle`, `maximumPoolSize`, `sampledAt`, `status`, `waitingThreads`
- recommendation evidence: `budgetCap`, `maxWaitingThreads`, `meanAcquireTime`, `meanConnectionHoldTime`,
  `peakActiveConnections`, `samples`, `samplesWithWaitingThreads`, `slowHoldThreshold`, `threadsEverWaiting`,
  `window`

`meanConnectionHoldTime` and `meanAcquireTime` are window means (`Δ TOTAL_TIME / Δ COUNT`). Nothing records the
`hikaricp.connections.usage` MAX, a histogram bucket or a percentile, and the runs did not configure any (no
`management.metrics.distribution.*` property). So "evaluate P_max or P_share against the S3 data" is impossible:
new runs are needed (step 1).

**Finding 2: the acquire/hold mean ratio, from the committed data, per run** (evaluated polls only: `generatedAt`
between driver start + 360 s and start + 450 s, whole seconds, the rule of `scripts/petclinic/evaluate.sh`; only
polls that carry a recommendation have the two means):

| Scenario | Run | Evaluated polls | Polls with both means | Mean hold | Mean acquire | acquire / hold |
|---|---|---|---|---|---|---|
| S1 | 1 / 2 / 3 | 3 / 4 / 3 | 0 / 0 / 0 | n/a | n/a | n/a (no recommendation, so no means published) |
| S2 | 1 | 2 | 2 | 1 ms | 61–69 ms | 61–69 |
| S2 | 2 | 3 | 3 | 1 ms | 12 ms | 12 |
| S2 | 3 | 3 | 3 | 1 ms | 20–23 ms | 20–23 |
| S3 | 1 | 3 | 3 | 142–143 ms | 762–769 ms | 5.4 |
| S3 | 2 | 3 | 3 | 141–142 ms | 775–778 ms | 5.5 |
| S3 | 3 | 3 | 3 | 145–146 ms | 765–767 ms | 5.2–5.3 |
| S4 | 1 / 2 / 3 | 4 / 4 / 4 | 0 / 0 / 0 | n/a | n/a | n/a (DECREASE evidence has no means) |

On these six runs a cut somewhere between 5.5 and 12 would put S3 on one side and S2 on the other. It is still
rejected as a discriminator, for reasons the data show:

- **Same direction in both.** In S2 and in S3 acquire is several times the hold: threads queue for a connection in
  both. A ratio above 1 is the signature of a queue, not of where the queue comes from; the only reading the ratio
  supports ("acquire ≫ hold, so the pool is the narrow point") gives INCREASE in both scenarios.
- **The cut would be fitted after seeing the data**, on 3 seeds of one lock pattern, which is the overfitting the
  brief rejects. Nothing predicts where the cut belongs before the data.
- **S2's ratio is not known to better than a factor of about 3.** The evidence rounds the hold mean to whole
  milliseconds (`Format.millis`): "1 ms" is anything from 0.5 to 1.5 ms, so S2's ratios are 8–138, not 12–69. The
  gap to S3 (5.5) is smaller than the rounding uncertainty at its low end.
- **The ratio scales with the queue, i.e. with load.** S2 ran at 1,200 req/s and S3 at 50 req/s. Acquire time grows
  with the number of waiting threads; the same lock at a higher request rate would raise S3's ratio towards S2's.
  The committed data have no second rate per scenario, so they cannot show where either ratio would land.

So the acquire/hold ratio cannot be shown to separate S2 from S3 on the committed data in any way that is not a
post-hoc fit, and it is not a candidate below.
