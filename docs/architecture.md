# Architecture

## Modules and dependencies

```
                    springtune-model   (no dependencies: Finding, RuleDescriptor, Knob, Recommendation, ConnectionBudget)
                     ▲             ▲
                     │             │
 springtune-check-engine        springtune-spring-boot-autoconfigure
   (spring-boot core, javaparser,    (spring-boot-autoconfigure; optional: actuator,
    snakeyaml, jackson)               micrometer, HikariCP)
     ▲            ▲                            ▲
     │            │                            │
 check-cli   maven-plugin             spring-boot-starter (+ actuator starter)
                                               ▲
                                               │
                                  samples/broken-config (also uses the engine in tests)
```

Key principle (plan §13): the CI check and the runtime starter share `springtune-model`. A rule ID means the
same thing everywhere. The connection-budget formula exists exactly once (`ConnectionBudget`), used by
ST-HIKARI-004 in CI and by the recommendation cap at runtime.

## Check engine data flow

```
CheckRequest ──► SettingsLoader (springtune.yml, validated) ──┐
             └─► ProjectDiscovery ─► for each module:          │
                   ProjectConfigLoader ─► ResolvedConfig       │   (Spring loaders + Binder + origins)
                   BuildInspector      ─► BuildInfo            │   (Maven/Gradle dependencies, Boot version)
                   JavaSources         ─► parsed ASTs          │   (JavaParser, syntactic)
                 ModuleModel ───────────────────────────────── ┤
DeploymentScanner (lazy, per module) ─► ReplicaInfo ───────────┤
                                                               ▼
                     ProjectContext ─► Rule × 13 (ServiceLoader) ─► Finding*
                                                               ▼
                     severity overrides ─► suppressions ─► sort ─► CheckReport ─► Reporters (text/md/json/sarif)
```

Design rules that protect trust (low false positives):

1. **Spring-faithful resolution.** Config is never parsed by hand. Spring Boot's loaders and `Binder` decide
   values, so relaxed binding and conversion cannot diverge from runtime behavior.
2. **Silence over guessing.** Rule families run only for detected technologies. Unresolvable values, unknown
   replica counts and ambiguous manifests become *Not evaluated* diagnostics, never findings.
3. **Explain everything.** Each finding has `message` (with concrete values), `why` (the incident),
   `fix`, a `file:line`, and a docs page.
4. **Deterministic output.** No timestamps or absolute paths, stable ordering, insertion-ordered JSON. A
   golden-file test guards it.

## Runtime starter

```
SpringTuneAutoConfiguration (springtune.enabled, default on)
 ├─ TuningService ── discovers on first use ─► HikariComponent (per HikariDataSource)
 │                                            ExecutorComponent (per ThreadPoolTaskExecutor)
 │      sample(): every component records a sample into its SampleWindow (bounded by retention)
 │      ADVISE: HikariAdvisor / ExecutorAdvisor (pure functions) ─► Recommendation*
 ├─ TuningSampler (SmartLifecycle, one daemon thread, fixed rate)
 ├─ TuningEndpoint  @Endpoint(id = "tuning"), @ReadOperation only (needs exposure)
 └─ MeterBinder     gauge springtune.recommendations
```

Optional libraries are isolated: Hikari and Micrometer types are referenced only after a class-presence
check (`MicrometerSupport`, `TimerReader`), so the starter loads in applications without them.

## Decisions

| Decision | Alternatives | Why |
|---|---|---|
| Maven multi-module | Gradle | Available in the toolchain; the first plugin targets Maven; one build tool keeps CI simple |
| Engine bundles Spring Boot 4.1 core | Hand-written YAML merge | Correct precedence and relaxed binding; origins give `file:line` for SARIF. Boot 2.4+ config semantics are stable. |
| JavaParser without symbol solving | Spoon, full classpath resolution | Needs no compiled classpath in CI; rules are written to be conservative |
| Rules first, no ML | Statistical models | Explainability is the trust driver (plan §2); rules are testable with synthetic signals |
| Starter computes, never applies | Apply with auto-revert | Trust ladder: apply needs SLO verification and load tests, which are out of scope for this iteration |
| Recommendations need full window coverage | Act on partial data | Avoids advice from a warm-up spike |
