# Deep-dive template (one every two weeks)

Copy to `docs/deep-dives/<RULE-ID>-<short-slug>.md`. One rule per deep dive. Target 1,200 to 1,800 words.
Before publishing, run `scripts/content/check-claims.sh` and add every number to
[docs/launch/claims.md](../launch/claims.md).

Rules for the content:

- **Every number traces to a committed artifact** (corpus report, re-run, raw JSON, a test, a benchmark) and names
  the CLI version that produced it. No artifact, no number.
- **Incident stories are composites.** Say so in the text. No real or real-sounding company names, no invented
  quotes, no "a Fortune 500 bank". Illustrative arithmetic is labeled as illustrative.
- **Lead with the CI check and read-only advice.** Apply mode, if mentioned at all, is "experimental, opt-in; 5 of 6
  pre-registered bars met, no production user", linked to the iteration-4 brief §2.
- **Say what the rule does not catch.** Every rule trades recall for precision somewhere; name the trade.
- **Install snippets use the current release only** (the check script fails on any other version).

---

```markdown
# <Plain-language title: the failure, not the tool>

*Deep dive #<n> · rule [<RULE-ID>](../rules/<RULE-ID>.md) · SpringTune <version> · numbers traced in
[docs/launch/claims.md](../launch/claims.md)*

<2–3 sentences: the default or mistake, its value, and what it turns a small problem into.>

## How it goes wrong

The story below is a composite of a common incident pattern, not a report about any real company.

<Setup: the service, its traffic, the defaults involved.>

| Time | What happens |
|---|---|
| 0 s | <trigger> |
| … | <escalation, with the arithmetic> |
| … | <how it ends, and why it lasted longer than the trigger> |

<What the fixed configuration changes in the same story.>

## How often it shows up

<Counts from the latest corpus run: findings, repositories, not-evaluated modules and why, false positives in the
review, tutorial-repo share. One real, linked example at a pinned commit.>

## The fix

<Config or code, copy-pasteable. How to pick the value. What to do with the resulting exception.>

## How the rule catches it

<What it reads, exact conditions, severity, options, when it skips (not evaluated) and what it deliberately
misses. springtune.yml snippet for options and for a suppression with a reason.>

## See it fail

<Link to examples/broken-config-ci/ and to the install section of the launch post.>

## Related rules

<2–3 links.>
```

## Cadence and candidates

| # | Rule | Topic | Corpus signal (latest run) | Planned |
|---|---|---|---|---|
| 1 | ST-HIKARI-001 | Connection acquire timeout | 45 findings, 12 repos | launch |
| 2 | ST-HTTP-001 | HTTP clients without timeouts | 6 findings, 5 repos | launch |
| 3 | ST-TX-001 | Transaction and query timeouts ([written](ST-TX-001-transaction-query-timeouts.md), iteration 6) | 70 findings, 16 repos (1 false positive) | launch + 2 weeks |
| 4 | ST-ASYNC-002 | Unbounded executor queues | 7 findings, 5 repos | launch + 4 weeks |
| 5 | ST-HIKARI-004 | Connection budget across replicas | needs `springtune.yml` (0 in a cold run) | launch + 6 weeks |

Pick the next one by rule requests and false-positive reports first, corpus counts second.
