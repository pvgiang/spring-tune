# Iteration 3: Delivery brief (guarded apply, adaptive limiter, adoption signals)

> Follows [iteration 2](iteration-2-brief.md). Input: its §5 items 1, 3 and 4, and the
> [plan](../business-and-implementation-plan.md) §16 build steps 4–5 (milestone 0.5) and §17 (adoption).

## 1. Goal

> A team that already trusts SpringTune's advice can **apply one recommendation on one instance, after
> review, with guardrails that make the change bounded, verified and automatically reverted**. A team can
> also add an **adaptive concurrency limiter** that only observes until they switch it to enforce. And the
> repository can **measure the plan §9 gates** without phoning home. Nothing changes by default.

## 2. Challenge log

The design was produced by the Eng architect and attacked by the PO/SRE skeptic. Conflicts were resolved as follows.

| # | Raised by | Challenge | Decision |
|---|---|---|---|
| 1 | PO/SRE | Apply skips a trust-ladder rung: no design partner or measured accept rate yet (§9 gate 2). Ship a read-only "apply plan" instead and defer apply. | **Both, in order.** Every recommendation now carries an *apply plan* (property to commit, budget arithmetic, side effects), available in `advise`. Real apply ships as level 3, **opt-in, marked experimental**, behind every guardrail below. Plan §16 step 4 needs it for 0.5, and building it now with the skeptic's guardrails is cheaper than retrofitting them. Level 4 (autonomous) stays out. |
| 2 | PO/SRE | Apply mode with an unauthenticated write endpoint. | Write operation exists only when `mode=apply`. It is a **web-only** extension, so JMX gets no setter. **Startup fails if Spring Security is not on the classpath.** There is no "allow anonymous" switch. Optional `required-role`. Principal is recorded. |
| 3 | PO/SRE | Arbitrary values via the endpoint. | A request names a **current recommendation** (`ruleId` + `knobId`) and `expectedCurrent` (compare-and-set). Only its `recommendedValue` can be applied; HOLD cannot. Mismatch → 409. |
| 4 | PO/SRE | N replicas all raise at once → DB over `max_connections`. | Raising `maximumPoolSize` is refused unless `springtune.budget.*` is set; the per-instance cap (`ConnectionBudget`, same as ST-HIKARI-004) bounds it. |
| 5 | PO/SRE vs Eng | Eng's revert rule included global HTTP latency / 5xx. PO: an unrelated downstream incident would then revert (and possibly shrink) the pool mid-spike. Eng: mean-based signals are confounded by traffic shifts anyway. | **Revert only on the knob's own signals** (Hikari: timeouts per acquire, mean acquire time, mean hold time; executor: rejections). HTTP signals are recorded as evidence, never as a trigger. If throughput between baseline and verification windows differs by more than 2×, the verdict is INCONCLUSIVE (not REGRESSED/OK). |
| 6 | Eng | `on-inconclusive=revert` makes low-traffic services unapplyable; `keep` contradicts "every change verified". | Default `revert`, with the reason shown. Safe side wins; `keep` is an explicit opt-in. |
| 7 | PO/SRE | Revert oscillation. | Revert writes back the *before* value only if the live value still equals *after* (else SUPERSEDED). After a revert the knob is locked for `cooldown-after-revert` (60 m). One change in flight per process. |
| 8 | PO/SRE | DB is the real cause → more connections make it worse. | At apply time the guard re-evaluates the recommendation; a HOLD (ST-REC-HIKARI-002) has nothing to apply. Verification rule on hold time catches "more connections made each one slower". |
| 9 | PO/SRE | Change lost on restart, drift vs GitOps. | Changes are explicitly **ephemeral**: snapshot shows `configured` (startup) vs `effective` value and the property line to commit. Restart reverting it is intended. |
| 10 | PO/SRE | Hikari `minimumIdle` defaults to max (fixed pool). Raising max silently makes it dynamic. | If `minimumIdle == maximumPoolSize`, both move together and the audit shows both. Shrink is lazy in Hikari (documented). |
| 11 | PO/SRE | Executor setter ordering throws (core > max). | Grow: max then core; shrink: core then max. Unbounded queue: only core is offered. Tested both directions. |
| 12 | PO/SRE | Kill switch needs a restart. | `POST {"action":"freeze"}` reverts the change in flight and refuses further applies until restart. `springtune.apply.enabled=false` is the static switch. |
| 13 | Eng | Write it ourselves or depend on Netflix `concurrency-limits-core`? | Depend on **0.5.4** (Apache 2.0, Dec 2025, one dependency: slf4j-api), behind our own facade so it can be vendored. Own Filter/WebFilter (Netflix's servlet module covers neither WebFlux nor our metrics). |
| 14 | PO/SRE | Limiter hurts at cold start, low traffic, on SSE/streaming/WebSocket, on health probes (→ K8s restarts), and amplifies retries. | `enabled=false` by default; when enabled, **shadow** (count "would reject", never reject) until `enforce=true`. Never limited: `/actuator/**`, the management port, `text/event-stream`, WebSocket upgrades. No rejection before `warmup-samples` (500). `min-limit` floor. 503 + `Retry-After`; docs warn about retry budgets. Async servlet requests hold a permit until complete/error/timeout. |
| 15 | PO/SRE | Telemetry endpoint: zero users → zero data, VPS + GDPR surface, "phones home" for a trust product. | **Not built.** Gates are measured with Central/Portal download stats, issue templates (incl. "fleet / approvals / budget" = paid trigger), and local `springtune.*` metrics. Revisit at >10 teams. |
| 16 | PO/SRE | Nothing is installable from Central; §9 gate 1 cannot be measured. | Release pipeline gets a JReleaser deploy to Maven Central, gated on repository secrets. Creating the Sonatype/Plugin Portal accounts and secrets is a human step; it cannot be done or verified from this environment. |
| 17 | Eng | No Docker daemon: Testcontainers PostgreSQL and Gatling cannot run here. | Real HikariCP + H2 and real in-JVM HTTP load tests here. Testcontainers/Gatling stay a follow-up. |

## 3. Scope

(Filled in at delivery.)

## 4. Acceptance criteria and evidence

(Filled in at delivery.)
