# Spring Config Safety → Self-Tuning — Open Source to Paid Plan (v2)

> Working name: **SpringTune** (placeholder).
> **v2 change:** repositioned after market research. Lead with *config safety & explainable recommendations*; autonomous tuning becomes an opt-in upgrade earned through trust.

---

## 1. Positioning

**One-liner:** *"Catch the Spring Boot config that will take you down — before production does. Then let it tune itself, when you're ready."*

**Who:** Spring Boot teams running many services on Kubernetes (platform teams, SREs, backend leads).

**Space we own:** the application layer — HikariCP, Tomcat/Jetty/Undertow threads, `@Async`/task executors, timeouts, Resilience4j, Kafka consumer concurrency, caches. Infra/JVM optimizers (Cast AI, Akamas, PerfectScale, StormForge) cover pods, nodes, heap and GC; we complement them.

---

## 2. Evidence behind the pivot (summary)

| Finding | Implication |
|---|---|
| >60% of Java apps on K8s keep default JVM settings (Akamas 2026) | Teams don't tune; defaults are a real risk |
| 64% of IT/software outages tied to config/change management (Uptime Institute, 2023) | Config safety is a budget-worthy pain |
| Pool-exhaustion postmortems: leaks, slow SQL, missing timeouts, pods scaled without a DB connection budget | Many incidents need **detection + explanation**, not knob turning; naive auto-growing pools can make it worse |
| 71% require human review of resource changes; only 27% allow auto-apply; 17% fully continuous (CloudBolt 2026) | Autonomous action is a weak first sell |
| Trust drivers: visibility 48%, guardrails 25%, instant rollback 23% | Lead with transparency; build guardrails/rollback before autonomy |
| FinOps 2026: big waste already captured; pre-deployment / shift-left is a top desired capability | A CI-time check fits current budgets |
| Spring Cloud integration of Netflix adaptive limits not released in 2+ years | Room for a maintained Spring Boot 3/4 integration |

**Not yet proven:** willingness to pay. Validate through usage signals (Section 9), not assumptions.

---

## 3. Product split (open core)

| Layer | License | What it does |
|---|---|---|
| **springtune-check** (Maven/Gradle plugin + CLI) | Apache 2.0 | Static analysis of config in CI: missing timeouts, unbounded executors, leak detection off, pool × replicas > DB `max_connections`, risky defaults |
| **springtune-starter** (Spring Boot starter) | Apache 2.0 | Runtime knob registry, `/actuator/tuning`, Micrometer signals, **explainable recommendations**, adaptive concurrency limit (opt-in) |
| **springtune-controller** | Commercial | Fleet view, cross-service **connection budget**, approvals, audit, policy, later: autonomous tuning with canary + rollback |
| **springtune-cloud** (later) | SaaS | Hosted controller |

**Rule for the line:** anything that makes *one service* safer is free. Anything that coordinates *many services*, involves *people* (approvals, RBAC, audit), or acts *autonomously at scale* is paid.

---

## 4. Trust ladder (product maturity = customer maturity)

1. **Observe** — show knobs, current values, saturation signals.
2. **Advise** — recommendations with a plain-language *why* and expected impact.
3. **Approve** — one-click apply after human review, with automatic rollback.
4. **Automate** — bounded autonomous tuning per knob, opted in individually.

Every feature ships at levels 1–2 first. Level 4 is never the default.

---

## 5. Phase 1 — Free CI check (months 0–2)

Fastest path to signal; no runtime risk for adopters.

**Rules v1 (start with ~15 high-confidence rules):**
- Missing/infinite timeouts: Hikari `connectionTimeout`, HTTP clients (RestClient, WebClient, Feign), JDBC query timeout, transaction timeout.
- `@Async` on `SimpleAsyncTaskExecutor` / unbounded queues.
- HikariCP: leak detection disabled, `maxLifetime` ≥ DB idle timeout, pool size outliers.
- **Connection budget:** `maximumPoolSize × max replicas (from HPA/Helm values) > DB max_connections` (user supplies DB limit).
- Tomcat `threads.max` vs pool size mismatches (threads waiting on too few connections).
- Resilience4j: circuit breaker without timeout; retries without backoff.

**Output:** CI annotations + a Markdown/SARIF report with severity, explanation, and suggested fix.

**Exit:** 10+ public repos or teams using it; rules produce low false positives on real projects (track ignore/suppress rates).

---

## 6. Phase 2 — Runtime starter (months 2–5)

- `@Tunable(min, max, default, owner)` + auto-discovery of common knobs.
- `/actuator/tuning`: knobs, values, bounds, owner, last change + reason.
- Signals via Micrometer: `hikaricp.connections.pending`, acquire time, executor queue depth/rejections, Tomcat busy threads.
- **Recommendation engine (rules first):** e.g. "pending > 0 for 5 min while DB CPU low → raise pool from 10 to 14 (bounded by connection budget)"; "acquire time high + slow queries → do NOT raise pool; investigate query X".
- Adaptive concurrency limit filter (Gradient/AIMD), off by default, maintained for Spring Boot 3.x and 4.x.
- Load-test suite (Testcontainers + Gatling/k6) proving recommendations and limiter behave under stress.

**Exit:** benchmark write-up on PetClinic + one design partner service showing an incident class caught or p99 improved.

---

## 7. Phase 3 — Adoption (months 3–7, overlaps)

- Content built around **real incident patterns**: "The 5 Spring configs behind most pool-exhaustion outages", with the CI rule that catches each.
- Channels: r/java, Show HN, Spring I/O / Devoxx / JUG talks (online), dev.to, Baeldung-style tutorials.
- Opt-in anonymous telemetry: which rules fire most, which recommendations get accepted.
- Recruit 3–5 design partners from active users (GitHub issues, discussions).

---

## 8. Phase 4 — Paid controller (months 6–12)

Build only when the trigger in Section 9 fires.

**Paid features (in order):**
1. **Fleet view** — every service, knob, rule finding, recommendation history.
2. **Connection budget** across services sharing a database, recalculated as replicas scale.
3. **Approve & apply** — review queue, one-click apply via config push, automatic rollback on SLO regression.
4. **Governance** — audit log, RBAC, SSO, Slack/Teams notifications, change freeze windows.
5. **Autonomous mode (opt-in per knob)** — bounded slow loops, canary, cooldowns, kill switch.

**Architecture:** Spring Boot + PostgreSQL controller; reads Prometheus/OTel; pushes via Spring Cloud Config / Bus or Actuator; Angular UI; self-hosted (Docker + Helm) first.

**Licensing:** OSS repos Apache 2.0 with CLA; controller commercial with free non-prod license.

---

## 9. Validation without interviews — signals & gates

| Gate | Signal | Threshold to proceed |
|---|---|---|
| CI check has pull | Installs, stars from company-affiliated accounts, issues requesting rules | 10+ teams, rule requests arriving |
| Recommendations are trusted | Accept rate of recommendations (telemetry/opt-in) | ≥30% accepted |
| **Paid trigger** | Unprompted requests for fleet view, approvals, cross-service budget, audit | 3+ teams ask, 1+ agrees to paid pilot |
| Autonomy is wanted | Teams applying recommendations without edits repeatedly | Consistent acceptance on a knob before offering auto mode for it |

If gates fail: keep the OSS as a portfolio/career asset and pivot to **paid Spring performance audits** using the tool.

---

## 10. Pricing (hypotheses — test in pilots)

| Tier | Price | Includes |
|---|---|---|
| **Community** | Free | CI check, starter, recommendations, adaptive limiter |
| **Team** | ~$20–35 per service/month (min ~$300/mo) | Fleet view, connection budget, approve & apply, rollback, Slack |
| **Enterprise** | Annual (~$20k–60k+) | SSO/RBAC, audit export, air-gapped, autonomous mode, SLA |

Early revenue: **Spring production-readiness audits** delivered with the tool (fixed-price), which also produce case studies.

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Willingness to pay unproven | Gates in Section 9; audits as fallback revenue |
| False positives erode trust | Few high-confidence rules first; easy suppression with reason; track suppress rate |
| Auto-tuning amplifies incidents | Trust ladder; connection budget caps; "don't raise pool" rules when slow SQL is the cause |
| Infra optimizers move to app layer | Go deep on Spring specifics; integrate with them rather than compete |
| Solo bandwidth | CI check first (no runtime risk, small scope); narrow rule set |

---

## 12. First 30 days

- [ ] Repo created (Apache 2.0, CLA, CI), name/domain checked
- [ ] Config parser for `application.yml/properties` + profiles
- [ ] First 8 rules incl. timeouts, `@Async` executor, connection budget
- [ ] Maven plugin + CLI producing Markdown/SARIF report
- [ ] Run against 20 public Spring Boot repos; record findings and false positives
- [ ] Publish write-up: "What we found scanning 20 Spring Boot projects"

---

# Part 2 — Implementation Plan

> Timeline assumes part-time solo development. Weeks are calendar weeks; adjust proportionally to available hours. Each phase has a **target**, **scope**, **design**, **build steps**, **testing**, and **definition of done (DoD)**.

## 13. Overall architecture & repository layout

```
springtune/                          (Apache 2.0, public)
├── springtune-model/                Shared domain: Knob, Finding, Recommendation, Severity
├── springtune-check-engine/         Config + code analysis, rule API, built-in rules
├── springtune-check-cli/            picocli CLI (fat jar; GraalVM native later)
├── springtune-maven-plugin/         `mvn springtune:check`
├── springtune-gradle-plugin/        `./gradlew springtuneCheck`
├── springtune-github-action/        Action wrapper → SARIF upload
├── springtune-spring-boot-autoconfigure/
├── springtune-spring-boot-starter/  Runtime registry, actuator, recommendations
├── springtune-limiter/              Adaptive concurrency filter (servlet + WebFlux)
├── springtune-samples/              PetClinic-based demo + broken-config demo
└── springtune-loadtests/            Testcontainers + Gatling scenarios

springtune-controller/               (commercial, private repo)
├── controller-api/                  Spring Boot, PostgreSQL, Flyway
├── controller-ui/                   Angular
└── deploy/                          Docker, Helm chart
```

**Baseline tech choices**
- Java 17 baseline (widest Spring Boot 3 reach); CI matrix on 17 / 21 / 25.
- Spring Boot compatibility matrix: latest 3.x and 4.x lines.
- Build: Gradle (Kotlin DSL) or Maven — pick one; publish to Maven Central via JReleaser.
- CI: GitHub Actions; release on tag; Dependabot; OpenSSF Scorecard for credibility.
- Docs: MkDocs Material or Docusaurus on GitHub Pages; one page per rule.

**Key design principle:** the shared `springtune-model` is used by CI check, runtime starter, and controller, so a finding in CI, a recommendation at runtime, and an item in the fleet view are the same concept with the same rule IDs (e.g. `ST-HIKARI-002`).

---

## 14. Phase 0 — Foundations (weeks 1–2)

**Target:** a releasable empty skeleton so every later phase only adds features.

**Build steps**
1. Create multi-module repo, license, CLA bot (cla-assistant), CONTRIBUTING, CODE_OF_CONDUCT.
2. CI pipeline: build, unit tests, Spotless/Checkstyle, JaCoCo, compatibility matrix.
3. JReleaser config for Maven Central snapshot + release.
4. Define core model in `springtune-model`:
   - `Knob<T>` (id, type, min, max, default, current, owner, source)
   - `Finding` (ruleId, severity, location, message, why, fix)
   - `Recommendation` (knobId, from, to, reason, evidence, confidence)
5. Docs site skeleton + landing page with waitlist.

**DoD:** `0.0.1-SNAPSHOT` published; CI green across the matrix; docs site live.

---

## 15. Phase 1 — CI config check (weeks 3–8)

**Target:** a free tool any team can add in 5 minutes that catches risky Spring config before deploy.
**Success metric:** runs on 20 public Spring Boot repos with meaningful findings and false-positive rate under ~10%.

### Scope (v0.1 → v0.3)
- v0.1: config-only rules (YAML/properties, profiles).
- v0.2: source rules (`@Async`, HTTP clients without timeouts).
- v0.3: deployment-aware rules (Helm values / HPA → connection budget), SARIF + GitHub Action.

### Design
- **Project model builder**
  - Discover `application*.yml|yaml|properties` under `src/main/resources` (and config dirs).
  - Load with Spring's `YamlPropertySourceLoader` / `PropertiesPropertySourceLoader`; merge per profile in Spring's precedence order.
  - Resolve values with Spring's `Binder` so relaxed binding (`maximum-pool-size` = `maximumPoolSize`) works and defaults are known.
  - Detect Spring Boot version from build file to apply version-correct defaults.
- **Source scanner:** JavaParser over `src/main/java` for annotations and bean definitions (`@Async`, `@EnableAsync`, `TaskExecutor` beans, `RestClient`/`WebClient`/`RestTemplate` builders, Feign config).
- **Deployment scanner (optional input):** parse Helm `values.yaml`, Kustomize, or raw `HorizontalPodAutoscaler` YAML for `maxReplicas`; DB `max_connections` supplied via `springtune.yml`.
- **Rule API**
  ```java
  public interface Rule {
      String id();                 // ST-HIKARI-002
      Severity defaultSeverity();
      List<Finding> evaluate(ProjectModel model);
  }
  ```
  Loaded via `ServiceLoader` so teams can add custom rules later.
- **Suppressions:** `springtune.yml` (`suppress: [{rule: ST-ASYNC-001, reason: "..."}]`); reason is mandatory and counted in telemetry.
- **Reporters:** console, Markdown, SARIF 2.1.0 (GitHub code scanning), JSON.
- **Exit codes:** fail build on configurable severity threshold.

### Initial rules (v0.1–v0.3)
| ID | Rule |
|---|---|
| ST-HIKARI-001 | `connectionTimeout` left at default or > 5s for request-path services |
| ST-HIKARI-002 | `leakDetectionThreshold` not set |
| ST-HIKARI-003 | `maxLifetime` ≥ database idle/wait timeout (if provided) |
| ST-HIKARI-004 | **Connection budget:** `maximumPoolSize × maxReplicas` > DB `max_connections` (× safety factor) |
| ST-TOMCAT-001 | `server.tomcat.threads.max` far above pool size (threads will queue on connections) |
| ST-ASYNC-001 | `@EnableAsync` with no bounded executor (falls back to per-task threads) |
| ST-ASYNC-002 | Executor with unbounded queue |
| ST-HTTP-001 | HTTP client built without connect/read timeouts |
| ST-TX-001 | No transaction / query timeout configured |
| ST-R4J-001 | Retry without backoff, or circuit breaker without time limiter |

### Build steps
1. Project model builder + property resolution with profile tests (week 3–4).
2. Rule API, first 6 config rules, console + Markdown reporters, CLI (week 5).
3. Maven + Gradle plugins (week 6).
4. Source scanner + async/HTTP rules (week 7).
5. Deployment scanner, connection budget rule, SARIF, GitHub Action (week 8).

### Testing
- Golden-file tests: fixture projects → expected findings JSON.
- Profile precedence tests (default vs `prod` vs env overrides).
- Corpus test: script clones 20+ public Spring Boot repos, runs the check, stores results; review false positives manually and track rate per rule.

**DoD:** v0.3 on Maven Central and GitHub Marketplace; per-rule docs page; corpus report published as the launch blog post.

---

## 16. Phase 2 — Runtime starter & recommendations (weeks 9–18)

**Target:** a drop-in starter that shows live knob state and produces explained recommendations; **never changes anything by default**.
**Success metric:** one design partner runs it in staging/prod; ≥1 recommendation accepted and verified.

### Design
- **Auto-configuration** (`springtune-spring-boot-autoconfigure`), conditional on classes present (`@ConditionalOnClass(HikariDataSource.class)` etc.). Property prefix `springtune.*`; `springtune.mode=observe|advise|apply` (default `advise`).
- **Knob registry:** `TuningRegistry` collects `Knob`s from adapters + user `@Tunable` beans.
- **Adapters (read + optional runtime write):**
  | Knob source | Read | Runtime write mechanism |
  |---|---|---|
  | HikariCP pool size / timeouts | `HikariPoolMXBean`, `HikariConfigMXBean` | `HikariConfigMXBean.setMaximumPoolSize(...)` etc. |
  | `ThreadPoolTaskExecutor` | bean introspection + executor metrics | `setCorePoolSize` / `setMaxPoolSize` |
  | Tomcat threads | connector executor | resize underlying `ThreadPoolExecutor` |
  | Resilience4j | registries | read-only in v1 (recommend only) |
  | Kafka listener concurrency | listener container registry | read-only in v1 |
- **Signals:** read from Micrometer `MeterRegistry` already populated by Boot (`hikaricp.connections.pending`, `.acquire`, `.usage`, executor metrics, `tomcat.threads.busy`). Sliding windows (e.g. 1m/5m/15m) computed in-process.
- **Recommendation engine (rules, not ML):**
  ```java
  public interface RecommendationRule {
      String id();
      Optional<Recommendation> evaluate(SignalSnapshot s, KnobState k, Budget b);
  }
  ```
  Examples:
  - Pending connections > 0 for 5 min **and** acquire time high **and** query time normal → raise pool by step, capped by connection budget.
  - Pending > 0 **and** query time high → recommend *against* raising pool; point to slow queries (link to `ST-...` explanation).
  - Pool usage < 20% for 24h → lower max pool (frees DB connections for others).
- **Actuator endpoint** `@Endpoint(id = "tuning")`:
  - `GET` knobs, current values, bounds, recommendations with reasons.
  - `POST` apply (only when `mode=apply` and secured; disabled by default).
- **Guardrails in-process:** bounds, max step size, cooldown per knob, kill switch property, automatic revert if error rate or latency worsens within a verification window.
- **Adaptive limiter** (`springtune-limiter`): servlet `Filter` + WebFlux `WebFilter` wrapping Netflix `concurrency-limits` core (Apache 2.0) with Spring Boot 3/4 auto-config and Micrometer metrics; off by default.

### Build steps
1. Auto-config skeleton, registry, Hikari + executor adapters (weeks 9–10).
2. Signal windows from Micrometer; actuator read endpoint (week 11).
3. Recommendation rules v1 (pool up/down, don't-raise-on-slow-SQL, executor queue) (weeks 12–13).
4. Apply path with guardrails + auto-revert, behind `mode=apply` (weeks 14–15).
5. Adaptive limiter module (week 16).
6. Load tests, docs, sample app, release v0.5 (weeks 17–18).

### Testing
- Unit tests for each rule with synthetic signal snapshots.
- Integration: Testcontainers PostgreSQL + sample app; inject slow queries and traffic spikes; assert correct recommendation (including "don't raise").
- Load: Gatling scenarios (steady, spike, upstream degradation); assert limiter keeps p99 bounded and apply-mode converges without oscillation.
- Compatibility matrix across Boot 3.x/4.x, MVC and WebFlux, virtual threads on/off.

**DoD:** v0.5 released; benchmark write-up; zero unsafe changes in load tests (all changes within bounds, all regressions auto-reverted).

---

## 17. Phase 3 — Adoption engineering (weeks 12–28, parallel)

**Target:** turn usage into evidence for the paid gate.

**Build steps**
1. **Opt-in telemetry** (`springtune.telemetry.enabled=false` by default): anonymous counts of rules fired, suppressions, recommendations shown/accepted. Tiny HTTPS endpoint (Spring Boot + PostgreSQL on a small VPS); publish what is collected.
2. **Docs per rule** with real incident pattern, why it matters, fix snippet.
3. **Samples:** "broken-config" demo repo that fails the check and a Grafana dashboard JSON for the starter metrics.
4. **Issue templates:** "rule request", "false positive", "feature: fleet / approvals / budget" (the last one feeds the paid trigger).
5. **Content cadence:** one rule deep-dive post every 2 weeks.

**DoD:** metrics dashboard tracking Section 9 gates; 3–5 design partners identified.

---

## 18. Phase 4 — Paid controller (weeks 24–48, only after the paid trigger)

**Target:** paid pilot with ≥1 customer using fleet view + connection budget + approve & apply.

### Design changes vs v1 idea
- **Agent-pull model instead of controller-push:** the starter (agent) opens outbound HTTPS to the controller, sends heartbeat + knob snapshot + recommendations, and pulls *desired state*. Works behind firewalls, no inbound access to services, and mirrors GitOps-style reconciliation that platform teams already trust.
- **Desired-state reconciliation:** controller stores desired knob values; agent applies within its local bounds, reports actual value and outcome. Local guardrails always win over controller.

### Components
| Component | Implementation |
|---|---|
| Agent protocol | REST/JSON over HTTPS, versioned (`/v1/agents/{id}/sync`); mTLS or token auth |
| Inventory | Services, instances, knobs, datasource fingerprint (hashed JDBC URL) |
| Fleet view | Angular UI: services × knobs × findings × recommendations; history timeline |
| Connection budget | Group instances by datasource fingerprint; sum `maxPool × live instances` (and `maxReplicas` from CI data); alert and block approvals that exceed budget |
| Approve & apply | Review queue → set desired state → canary (subset of instances) → verify window → roll out or roll back |
| Verification | Query Prometheus HTTP API (or OTel backend) for p99/error rate before vs after; configurable SLO thresholds |
| Governance | Spring Security OIDC SSO, RBAC (viewer/approver/admin), append-only audit table, Slack/Teams webhooks, freeze windows |
| Autonomous mode | Per-knob opt-in; slow loop (minutes) uses same recommendation rules, auto-approves within tighter bounds |
| Licensing | Signed license file (JWT, offline verification) with service limits; free non-prod license |
| Packaging | Docker image, Helm chart, PostgreSQL via Flyway migrations |

### Build steps
1. Agent sync protocol in starter + controller inventory (weeks 24–27).
2. Fleet view UI (weeks 28–31).
3. Connection budget service + alerts (weeks 32–34).
4. Approve & apply, canary, verification, rollback (weeks 35–40).
5. SSO, RBAC, audit, notifications (weeks 41–44).
6. Helm packaging, licensing, pilot onboarding guide (weeks 45–48).
7. Autonomous mode only after pilots show consistent acceptance on specific knobs.

### Testing
- Contract tests for agent protocol across starter versions (backward compatibility).
- Kind/k3d cluster e2e: multiple sample services sharing one PostgreSQL; scale replicas and verify budget alerts; inject regression and verify rollback.
- Security: OWASP dependency check, authZ tests per role, audit immutability test.
- Chaos: controller down → agents keep last known good values and keep working.

**DoD:** paid pilot live; controller survives controller outage, bad recommendation, and DB budget breach scenarios in e2e tests.

---

## 19. Milestone summary

| Week | Milestone | Release |
|---|---|---|
| 2 | Skeleton, CI, docs site | 0.0.1-SNAPSHOT |
| 5 | CLI with config rules | 0.1 |
| 8 | Maven/Gradle plugins, source + budget rules, SARIF, Action | 0.3 + launch post |
| 13 | Starter observe/advise + recommendations | 0.4 |
| 18 | Apply mode with guardrails, limiter, benchmarks | 0.5 |
| 28 | Telemetry-backed gate review | go / no-go for paid |
| 40 | Controller: fleet, budget, approve & apply | pilot beta |
| 48 | Governance, Helm, licensing | 1.0 paid pilot |

## 20. Engineering quality bar (all phases)

- Every rule and recommendation has an ID, docs page, test fixture, and explanation text.
- Nothing changes production by default; every write path has bounds, cooldown, revert, and audit.
- Semantic versioning; deprecations announced one minor version ahead.
- Performance budget for the starter: negligible overhead (verify with JMH and load tests; publish numbers).
