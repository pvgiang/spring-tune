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

### Review round (adversarial reviewers against the implementations)

| # | Raised by | Finding | Decision |
|---|---|---|---|
| R1 | Limiter reviewer | **Health probes were limited** with `spring.mvc.servlet.path=/api` (actuator at `/api/actuator/**`), with `management.endpoints.web.base-path=/`, and for `/livez`/`/readyz` (`add-additional-paths`). Under overload this can turn into 503 probes and K8s restarts, which row 14 forbids. | Fixed. `RequestPolicy` resolves the servlet path, root base path and probe paths. Regression tests on real Tomcat (`ServletPathBypassTest`). |
| R2 | Limiter reviewer | `Accept: …text/event-stream` / `Upgrade: websocket` bypass the limit on any endpoint. | Documented, not changed. The limiter is overload protection, not abuse control; narrowing the Accept rule breaks real clients (e.g. `application/json, text/event-stream`). |
| R3 | Limiter reviewer | Is the **vegas** default (implementer deviation from gradient2) justified? | Kept. Gradient2 drifted to max-limit in shadow mode and collapsed to 8–10 with 20% fast responses in a simulation; Vegas held. The doc now states it trades some throughput for latency and rests on one synthetic downstream. |
| R4 | Limiter reviewer | Client-side p99 assertion flaked on a loaded 4 vCPU box (client threads share CPU with the server). | Assertion removed; the server-side p99 assertion (`http.server.requests`) proves the claim. |
| R5 | Apply reviewer | **High:** the bounded audit could evict the in-flight change's record. `freeze` then reverted but did not freeze (500), and confirmations/reverts were lost. | Fixed. The in-flight record owns its entry. Two regression tests failed before the fix. |
| R6 | Apply reviewer | **Medium:** Spring Security's `AnonymousAuthenticationToken` (non-null principal, e.g. WebFlux `anonymous()`) counted as authenticated. | Fixed: anonymous is treated as no caller (401). `ApplyPermitAllWebTest` proves a `permitAll()` app still refuses. |
| R7 | Apply reviewer | **Low:** CR/LF in `reason` could forge `springtune.audit` log lines. | Fixed: escaped. |
| R8 | Apply reviewer | The budget cap is **per pool**: two pools on one database each get the full cap. | Documented. Knowing which pools share a database is the paid controller's cross-service budget (plan §8). |
| R9 | Apply reviewer | `apply` pins `expectedCurrent` but not the value; a fresh evaluation could move the target after review. | Fixed: optional `expectedTarget` (409 `STALE_EXPECTED_TARGET` on mismatch); docs recommend sending it. |
| R10 | Apply reviewer | Anonymous refusal floods can push genuine entries out of the in-memory audit. | Fixed: unauthenticated refusals are logged, counted and published, but not kept in memory. |
| R11 | Apply reviewer | Real-timing integration test failed 2/9 under machine load (Hikari fills connections asynchronously). | Test waits up to 5 s for the fill before judging; the safety assertion (total ≤ new max) is unchanged. |
| R12 | Apply reviewer | A pool raise that really removes a bottleneck can more than double throughput → INCONCLUSIVE → revert. | Accepted per row 5 and 6: with `max-step=0.5` closed-loop gains stay near 1.5×; the reason is shown. |

## 3. Scope

| In | Out (where it goes) |
|---|---|
| **Apply plan** on every INCREASE/DECREASE in `advise` mode: property lines to commit, budget arithmetic, side effects, "ephemeral" | Fleet-wide apply, config push, persistent audit (paid controller, plan §8/§18) |
| **`springtune.mode=apply`** (experimental, trust level 3): web-only write operation, Spring Security required, compare-and-set on current recommendation, budget/step/growth/cooldown guard, one change in flight, verification on the knob's own signals with auto-revert, freeze, audit log + events + `springtune.changes{outcome}` | Autonomous mode (level 4); WebFlux security end-to-end test; executor R6 end-to-end |
| **`springtune-limiter`** (separate opt-in artifact): Netflix `concurrency-limits-core` 0.5.4 behind our facade, servlet Filter + WebFlux WebFilter, shadow by default, probe/SSE/WebSocket/management bypass, warmup, 503 + `Retry-After`, Micrometer metrics | Per-route partitions; virtual-thread tuning; protection before the filter (Tomcat accept queue) |
| **Adoption signals**: issue forms (false positive, rule request, recommendation feedback, fleet/approvals/budget = paid trigger, bug), `docs/adoption/gates.md`, Grafana dashboard + checker | Telemetry endpoint (row 15) |
| **Release pipeline**: `release` profile (sources, javadoc, JReleaser → Central, stage `UPLOAD`), publish job gated on secrets, Gradle Plugin Portal build, `docs/releasing.md` | Account creation, GPG key, secrets, namespace verification (human steps, `docs/releasing.md`) |

## 4. Acceptance criteria and evidence

| ID | Criterion | Verification | Result |
|---|---|---|---|
| AC-3.1 | Nothing changes by default: `advise`/`observe` have no write operation (POST rejected), no JMX setter; only `*Writer` classes call pool/executor setters | `SpringTuneAutoConfigurationTest`, `BrokenConfigApplicationTest`, `WriterIsolationTest` | ✅ |
| AC-3.2 | Apply plan shows property diff, budget arithmetic, `minimumIdle` effect and "ephemeral" | `ApplyPlansTest`, `HikariApplyIntegrationTest` | ✅ |
| AC-3.3 | `mode=apply` without Spring Security fails startup; unauthenticated 401, anonymous 401 even with `permitAll`, wrong role 403, right role 200 with `who` | `SpringTuneAutoConfigurationTest` (FilteredClassLoader), `ApplySecurityWebTest`, `ApplyPermitAllWebTest`, `TuningApplyWebExtensionTest` | ✅ (servlet; WebFlux unit-level only) |
| AC-3.4 | Every guard refusal has a code and status; concurrent applies write exactly once | `ApplyGuardTest`, `ChangeControllerTest.concurrentAppliesWriteOnce` | ✅ |
| AC-3.5 | Real HikariCP + H2: recommended raise 2→3 applied, total connections ≤ 3, CONFIRMED, audit complete | `HikariApplyIntegrationTest` (a) | ✅ |
| AC-3.6 | Real HikariCP + H2: DB slows with more sessions → R2 regression → REVERTED to 2/2, knob locked 60 m | `HikariApplyIntegrationTest` (b), `ChangeControllerTest` | ✅ |
| AC-3.7 | Executor writes in both directions, bounded and unbounded queue, no `IllegalArgumentException`; rejection counter keeps original policy | `KnobWriterTest` | ✅ |
| AC-3.8 | Limiter off by default; shadow when enabled; invalid settings fail fast; no beans outside web apps or without the Netflix jar | `LimiterAutoConfigurationTest` | ✅ |
| AC-3.9 | Servlet overload (160 callers, downstream capacity 10): enforce cuts server p99 by > 2× vs off, every 503 has `Retry-After` and appears in `http.server.requests`, `/actuator/health` always 200, inflight back to 0 | `ServletOverloadTest` | ✅ off 351–435 ms → enforce 66–108 ms p99 |
| AC-3.10 | WebFlux overload (Netty, 80 callers) same properties, client cancel releases the permit | `ReactiveOverloadTest` | ✅ off 234–267 ms → enforce 64–87 ms p99 |
| AC-3.11 | Shadow mode never rejects under overload; `shadow_rejected > 0` | `ServletOverloadTest` | ✅ 0 × 503, 1,285–1,470 shadow |
| AC-3.12 | Probes, SSE, WebSocket, management port, servlet-path actuator, async (DeferredResult/Callable incl. timeout) handled correctly | `ServletAsyncAndBypassTest`, `ServletPathBypassTest`, `RequestPolicyTest` | ✅ (separate management port unit-level only) |
| AC-3.13 | Issue forms, gates doc, Grafana dashboard (structure-checked), README links | `scripts/check-grafana-dashboard.py`, YAML parse | ✅ (dashboard not loaded in a real Grafana: no Docker) |
| AC-3.14 | Release profile builds sources/javadoc and passes a JReleaser dry run; normal builds unaffected | local dry run with a throwaway key (see `docs/releasing.md`) | ✅ (real upload needs accounts) |
| AC-3.15 | Nothing regresses: full reactor on Boot 4.1.2 and the Boot 3.5.16 compatibility build | `mvn -B verify -DskipITs`; `mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip -DskipITs` | see §6 |

## 5. Needs a human

1. **groupId namespace.** Central verifies `io.github.winky` only for the GitHub account `winky`; this repository is `pvgiang/spring-tune`. Either control that account, use an owned domain, or move to `io.github.pvgiang` **before** the first Central release (cheap now, expensive after).
2. Central account + user token, GPG key, the five secrets, then press *Publish* once (`docs/releasing.md`).
3. Create the issue labels (`gh label create …` in `docs/adoption/gates.md`).
4. Gradle Plugin Portal account and keys.

## 6. Results

| Build (local, JDK 21) | Result |
|---|---|
| `mvn -B verify -DskipITs`, Boot 4.1.2, all 12 modules | ✅ BUILD SUCCESS |
| `mvn -B verify -Dspring-boot.version=3.5.16 -Dinvoker.skip -DskipITs` (CI `boot3` job), all modules | ✅ BUILD SUCCESS, 247 tests |
| Starter modules after the review fixes (autoconfigure, starter, sample), Boot 4.1.2 | ✅ |
| Maven plugin invoker ITs | ✅ 2/2 |
| Gradle plugin functional tests (Gradle 8.14.3) | 3/4. The version-catalog fixture failed because Gradle in this container could not resolve `spring-boot-starter-data-jpa:3.5.16` from Maven Central; the plugin reported it as designed (iteration 2, challenge 4). No file in the Gradle plugin, engine, model or their tests changed in this iteration; CI's `gradle` job is the authority. |

Not verified here: the JDK 17/25 CI matrix, Docker-based suites, a real Grafana import, a real Central upload.

## 7. Next (in plan order)

1. Do the human steps in §5 and release 0.3.0, so §9 gate 1 becomes measurable.
2. Find a design partner to run `advise` (then `apply`) in staging; record accepted recommendations through the feedback issue form (§9 gate 2).
3. Testcontainers PostgreSQL + Gatling suites as a CI-only job (Docker), including 3 replicas raising at once against a real `max_connections`.
4. WebFlux end-to-end security test for apply; executor R6 end-to-end; limiter with virtual threads and Spring Security in front.
5. Gate review at week 28 (plan §19): go / no-go for the paid controller.
