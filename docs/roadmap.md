# Roadmap

> **Current release: 0.4.0** (2026-11-09), the first on Maven Central. The steps below were written after 0.3.0 and
> keep their status notes; step 3, adoption, is the current work ([iteration-5 brief](delivery/iteration-5-brief.md)).

Where SpringTune stands against the [plan](business-and-implementation-plan.md), and what comes next, in order.
Each step names its exit signal, so progress is measured by evidence rather than by features shipped.

## Where 0.3.0 leaves us

| Plan phase | Status after 0.3.0 |
|---|---|
| Phase 0, foundations (§14) | Done, except the docs site, CLA bot and OpenSSF Scorecard |
| Phase 1, CI check, milestone 0.3 (§15) | **Done**: 13 rules, CLI, Maven + Gradle plugins, SARIF, GitHub Action, corpus report (0.5% false positives) |
| Phase 2, runtime starter, milestones 0.4–0.5 (§16) | Built: observe/advise, apply plans, guarded apply (experimental), adaptive limiter. **Open**: Docker-based load suites, JMH overhead numbers, benchmark write-up, a design partner |
| Phase 3, adoption (§17) | Instruments ready: issue forms, gates page, Grafana dashboard. Content and outreach not started |
| Phase 4, paid controller (§18) | Not started, and gated by the §9 paid trigger |

## Step 1: release 0.3.0 (week 0)

> **Status:** 0.3.0 shipped as a GitHub Release only; the Maven Central job was skipped because the secrets were
> not set. Items 1–3 move to **0.4.0**, the first Central release ([releasing.md](releasing.md#releasing-040)).
> Item 5 is [`post-release-smoke.yml`](../.github/workflows/post-release-smoke.yml).

1. One-time setup in [releasing.md](releasing.md): the Central account as `winkytek`, user token, GPG key, the five
   secrets, and the issue labels.
2. Tag `v0.3.0`. Check the bundle on central.sonatype.com and press *Publish*, then set `CENTRAL_STAGE=FULL`.
3. Publish the Gradle plugin (the `gradle-plugin-portal` workflow) once the engine is on Central.
4. List the Action on the GitHub Marketplace (release UI).
5. Add a CI smoke job: a clean Maven and Gradle project resolves every artifact and the plugin from Central and
   Portal with no local repository.

**Exit:** the smoke job is green and `io.github.winkytek.springtune` resolves from Central.

## Step 2: finish Phase 2 and release 0.4 (weeks 1–6)

The plan's 0.5 definition of done requires proof under load. 0.3.0 has in-JVM evidence only.

| Item | Why | Exit |
|---|---|---|
| Testcontainers PostgreSQL + Gatling suite as a CI-only job (Docker) | Plan §16 testing. A real `max_connections`, real slow queries | Raise, "don't raise" and revert verified against PostgreSQL |
| Three replicas raising at the same time against one database | Bar 2 of the pre-registered bar for apply ([iteration-4 brief §2](delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code)) | Total connections never exceed the budget |
| Apply over WebFlux security end to end; executor R6 end to end | Currently unit-level only | Tests green |
| Limiter with virtual threads and with Spring Security in front | Declared gaps in `limiter.md` | Tests green, or documented as unsupported |
| JMH overhead of the starter's sampler and the limiter filter | Plan §20: publish numbers | Numbers in the docs: [benchmarks.md](benchmarks.md) (both pre-registered gates pass) |
| Re-run the corpus's Gradle projects through the Gradle plugin | Iteration 2 next step; the blind spot of the literal build reader | Updated corpus report |
| Rules from `rule-request` issues; Kafka listener concurrency and cache rules from plan §1 | Phase 1 rule backlog | Each with docs page and fixture, false-positive rate tracked |

**Exit (0.4):** load-suite results published. Apply loses its "experimental" label only when **all six** bars of
[iteration-4 brief §2](delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code)
are met, including bar 1, a design partner running advise in production. Bars 2–6 are iteration 4's to prove;
bar 1 cannot be met with zero users, so apply stays experimental in 0.4.0.

## Step 3: adoption, in parallel (weeks 1–28)

| Activity | Feeds gate |
|---|---|
| Launch post from the [case study](case-study/what-we-found-in-20-spring-boot-projects.md): r/java, Show HN, dev.to | 1: teams using it |
| One rule deep dive every 2 weeks, each linking the CI rule that catches it | 1 |
| A "broken-config" demo repository that fails the check in CI | 1 |
| Docs site (MkDocs on GitHub Pages, one page per rule) | 1 |
| Recruit 3–5 design partners from issues and stargazers. Ask them to run `advise` in staging and report recommendations through the feedback form | 2: accept rate ≥ 30% |
| Benchmark write-up: PetClinic plus one design-partner service (plan §6 exit) | 2 |
| Monthly entry in the [gates log](adoption/gates.md) | all |
| CLA bot, Dependabot, OpenSSF Scorecard | trust |

## Step 4: gate review (week 28, plan §19)

Score the four gates in [gates.md](adoption/gates.md) honestly:

| Gate | Threshold | If it passes | If it fails |
|---|---|---|---|
| CI check has pull | 10+ teams, rule requests arriving | continue | more content, fewer features |
| Recommendations trusted | ≥ 30% accepted | promote apply from experimental, if all six bars of [iteration-4 brief §2](delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code) are met | improve rules; keep apply experimental |
| **Paid trigger** | 3+ teams ask for fleet, approvals, budget or audit; 1+ agrees to a paid pilot | **Step 5** | fallback: paid Spring production-readiness audits using the tool |
| Autonomy wanted | Repeated unedited acceptance on one knob | offer autonomous mode for that knob only | stay at level 3 |

## Step 5: paid controller (weeks 24–48, only if the paid trigger fired)

In plan §18 order. Each step also removes a known limit of 0.3.0:

1. **Agent sync protocol** in the starter (outbound HTTPS, desired state) and controller inventory. Removes "apply
   reaches one pod only".
2. **Fleet view** of services × knobs × findings × recommendations, with history. Removes "the audit is in memory
   and lost on restart".
3. **Cross-service connection budget**, grouped by datasource fingerprint. Removes "budget cap is per pool".
4. **Approve & apply fleet-wide** with canary, verification and rollback.
5. **Governance**: SSO, RBAC, append-only audit, Slack/Teams, freeze windows.
6. Helm chart, licensing, then the 1.0 paid pilot.
7. **Autonomous mode**, opted in per knob, only after gate 4.
