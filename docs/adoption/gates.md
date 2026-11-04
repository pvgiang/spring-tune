# Adoption gates: how they are measured

The [plan](../business-and-implementation-plan.md) §9 decides what gets built next with four gates. This page says
where each signal comes from, the exact query that counts it, and its value today. SpringTune has **no telemetry**
(see [below](#why-there-is-no-telemetry)), so every number here comes from public or owner-visible sources.

Snapshot: **2026-11-04**. Recount on the first working day of each month and append to the [log](#log).

## Gate table

| §9 gate | Signal | Source | How to count it | Threshold | Today |
|---|---|---|---|---|---|
| **1. CI check has pull** | Teams installing | Maven Central downloads of `springtune-check-engine` (every runner pulls it: CLI jar aside, Maven and Gradle plugins resolve it) | [central.sonatype.com](https://central.sonatype.com) → *Publishing* → *Namespaces* → `io.github.winky` → download statistics (owner only, monthly, unique IPs and downloads per artifact) | 10+ teams (see [teams](#what-counts-as-a-team)) | **n/a**: not on Central yet ([releasing](../releasing.md)) |
| | | Gradle Plugin Portal | Plugin page `plugins.gradle.org/plugin/io.github.winky.springtune`. As far as we know the Portal publishes no download counter; treat as unavailable until verified after the first publish | | **n/a**: not on the Portal yet |
| | | GitHub release assets (CLI jar, Maven zip) | `gh api repos/pvgiang/spring-tune/releases --jq '.[] \| {tag: .tag_name, d: [.assets[] \| {name, download_count}]}'` | | **0** (v0.1.0, all three assets) |
| | | GitHub Action users (public repos only) | `gh search code 'pvgiang/spring-tune' --extension yml --json repository --jq '[.[].repository.nameWithOwner] \| unique'` | | **0** known |
| | Stars from company-affiliated accounts | GitHub stargazers with a `company` | `gh api --paginate repos/pvgiang/spring-tune/stargazers --jq '.[].login' \| while read u; do gh api users/$u --jq 'select(.company != null) \| [.login, .company] \| @tsv'; done` | | **0** stars in total |
| | Rule requests arriving | Issue form *Rule request* | [`is:issue label:rule-request`](https://github.com/pvgiang/spring-tune/issues?q=is%3Aissue+label%3Arule-request) · `gh issue list --state all --label rule-request --json number --jq length` | at least 1 a month from outside the project | **0** |
| **2. Recommendations are trusted** | Accept rate of ST-REC-* recommendations | Issue form *Recommendation feedback* (outcome dropdown), triaged into an `outcome-*` label | accepted = `label:rec-feedback label:outcome-applied,outcome-edited,outcome-accepted`; all = `label:rec-feedback -label:invalid`; rate = accepted / all. Before triage: [`label:rec-feedback "rec-applied"`](https://github.com/pvgiang/spring-tune/issues?q=is%3Aissue+label%3Arec-feedback+%22rec-applied%22) and likewise `rec-edited`, `rec-accepted`, `rec-rejected` | **≥ 30%**, judged only from **10+** reports by 3+ teams | **unknown** (0 reports) |
| **3. Paid trigger** | Unprompted requests for fleet view, approvals, cross-service budget, audit | Issue form *Feature: fleet view / approvals / cross-service budget / audit* | teams = `gh issue list --state all --label paid-signal --json author --jq '[.[].author.login] \| unique \| length'` (then merge authors from the same company by hand); paid pilot = `label:paid-signal label:pilot-paid` (before triage: [`label:paid-signal "pilot-paid"`](https://github.com/pvgiang/spring-tune/issues?q=is%3Aissue+label%3Apaid-signal+%22pilot-paid%22)) | **3+ teams** ask, **1+** agrees to a paid pilot | **0** teams, **0** pilots |
| **4. Autonomy is wanted** | Teams applying a recommendation without edits, repeatedly, on the same knob | *Recommendation feedback* with outcome *applied as recommended* | per recommendation: `label:rec-feedback label:outcome-applied "ST-REC-HIKARI-001" in:title` vs. `label:rec-feedback "ST-REC-HIKARI-001" in:title` | proposal: **10+** reports on one recommendation, **≥ 80%** applied as-is, by 3+ teams, none reverted | **unknown** (0 reports) |

Quality signal that guards gate 1 (not a §9 gate, but a rule nobody trusts brings no pull):

| Signal | Source | How to count it | Target | Today |
|---|---|---|---|---|
| False-positive rate per rule | Issue form *False positive* (rule ID in the title), plus the corpus run | confirmed FPs = `label:false-positive -label:invalid "ST-HIKARI-001" in:title`; rate = confirmed FPs / findings of that rule in the latest [corpus report](../corpus/2026-11-corpus-report.md) plus user reports | under 5% per rule; any confirmed FP becomes a regression test | **1 / 199** findings (0.5%) in the corpus; **0** issues |

Local signals a user can show us (they never leave their system): `springtune_recommendations`,
`springtune_changes_total{outcome}` and `springtune_limiter_requests_total{outcome}` in the
[Grafana dashboard](../grafana/README.md). A design partner sharing a screenshot of *Applied changes by outcome*
is the strongest evidence for gates 2 and 4 we can get without telemetry.

### What counts as a team

One GitHub organization, or one company named in an issue or profile. Several people from the same company are
one team. Download counts are **not** teams: a corporate Maven proxy (Nexus, Artifactory) downloads once for
hundreds of developers, and a CI farm without a cache downloads hundreds of times for one team. Use downloads as a
trend, and named teams (issues, Action users, stargazers with a company, design partners) for the threshold.

### Triage labels

Issue forms set only their own label, and only if it already exists. Create the labels once:

```bash
gh label create rule-request   --color 0e8a16 --description "Rule request (gate 1)"
gh label create false-positive --color d93f0b --description "Wrong finding; feeds the per-rule FP rate"
gh label create rec-feedback   --color 1d76db --description "Recommendation feedback (gates 2 and 4)"
gh label create paid-signal    --color 5319e7 --description "Fleet / approvals / budget / audit request (paid trigger)"
gh label create outcome-applied  --color c2e0c6 --description "Recommendation applied as recommended"
gh label create outcome-edited   --color c2e0c6 --description "Recommendation applied with another value"
gh label create outcome-accepted --color c2e0c6 --description "Agreed, not applied yet"
gh label create outcome-rejected --color f9d0c4 --description "Recommendation rejected"
gh label create pilot-paid       --color 5319e7 --description "Would pay for a pilot"
gh label create pilot-free       --color d4c5f9 --description "Would try a free pilot"
# 'bug' and 'invalid' already exist (GitHub defaults).
```

When triaging a *Recommendation feedback* or *fleet* issue, add the `outcome-*` or `pilot-*` label that matches
the dropdown. Labels can be corrected later; the free-text search on the bracketed tokens is only the fallback.

## Why there is no telemetry

The plan (§7, §17 step 1) proposed opt-in anonymous telemetry. Iteration 3 decided **not to build it**
([brief](../delivery/iteration-3-brief.md), challenge 15):

- **Zero users means zero data.** An opt-in channel with no installs measures nothing; issue forms and download
  statistics answer the same questions at this stage.
- **It is a liability to run.** A collector on a VPS is a service to secure, patch and keep up, and personal-data
  questions (GDPR) start with the first IP address it sees.
- **It contradicts the product.** SpringTune asks for trust to look at, and later change, production
  configuration. A tool like that "phoning home", even opt-in, is the first thing a security review flags.

**Revisit when gate 1 passes (more than 10 teams).** By then there are enough installs for aggregate counts to mean
something, and enough users to ask whether they want it.

### Rules any future telemetry must follow

1. **Off by default, everywhere.** Enabled only by an explicit `springtune.telemetry.enabled=true` (runtime) or
   `--telemetry` / `springtune.telemetry=true` (CI check). No distribution, sample or template turns it on.
   `DO_NOT_TRACK=1` and `SPRINGTUNE_TELEMETRY=false` always win.
2. **Counts, not content.** Allowed: rule IDs that fired and how often, suppression counts, recommendation IDs
   shown / applied / reverted, SpringTune, Spring Boot and Java versions, the runner (CLI, Maven, Gradle, Action,
   starter). Never sent: hostnames, IP addresses, application or service names, file paths, property values,
   source code, SQL, stack traces, principals or user names, environment variables, repository URLs.
3. **No stable identity.** At most a random installation ID, rotated at least monthly, never derived from
   hardware, host name, MAC address or repository. The collector drops source IP addresses at the edge and never
   stores them.
4. **Show before sending.** The exact payload is documented and versioned in this repository, logged once at
   startup when enabled, and printable without sending (`springtune telemetry --print`, `GET
   /actuator/tuning/telemetry`).
5. **Never in the way.** Fire-and-forget over HTTPS with a short timeout; never blocks startup or a build, never
   fails one, never retries in a loop, no third-party analytics SDK.
6. **Open and public.** The collector's source is in this repository, raw data is kept at most 13 months, and
   aggregates (with groups smaller than 5 installations removed) are published.
7. **A change to what is collected is a minor version bump** with a release-notes entry, and users who opted in
   to the old payload are not opted in to the new one automatically.

## Log

| Date | Central downloads (engine, month) | Release asset downloads (all) | Stars (with company) | Rule requests | Rec. feedback (accepted / all) | Paid-signal teams / paid pilots | FP issues (confirmed) |
|---|---|---|---|---|---|---|---|
| 2026-11-04 | n/a (not published) | 0 | 0 (0) | 0 | 0 / 0 | 0 / 0 | 0 (0) |
