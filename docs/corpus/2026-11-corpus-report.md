# Corpus run: 20 public Spring Boot repositories (2026-11-03)

Plan §15 exit criterion: *"runs on 20 public Spring Boot repos with meaningful findings and false-positive
rate under ~10%"*. **Result: met. 199 findings, 1 false positive (0.5%), after fixing 6 false-positive
classes that the first run exposed (first run: at least 61 of 307 findings were wrong, 20%).**

## Method

- **Corpus:** [`scripts/corpus/repos.txt`](../../scripts/corpus/repos.txt), 20 repositories chosen for
  diversity: reference apps, microservice suites, admin platforms, large products; Maven and Gradle; Spring
  Boot 1.3 to 4.1; JPA, MyBatis, jOOQ; MVC and WebFlux; Feign, Ribbon, Config Server, Nacos.
  `zhoutaoo/SpringCloud` was replaced by `jeecgboot/JeecgBoot`: its code lives entirely in git submodules.
- **Run:** `scripts/corpus/run-corpus.sh <work-dir> <out-dir>` clones each repo shallow and runs
  `springtune check --fail-on NONE --format json`. Static analysis only: no corpus code is built or run.
  Default settings, no `springtune.yml`, active profiles as each repo declares them.
- **Reproduce:** commit SHAs are in [`results/2026-11-03/shas.tsv`](results/2026-11-03/shas.tsv); every
  per-repo JSON report is in [`results/2026-11-03/`](results/2026-11-03/). Summary:
  `python3 -I scripts/corpus/summarize.py docs/corpus/results/2026-11-03`.
- **Classification.** A finding is a *false positive* when its claim is wrong for the effective
  configuration or code (wrong pool, wrong value, risk that cannot occur). It is *unverifiable* when the
  configuration that decides it is not in the repository (remote config, custom-prefix pools).
- **Review coverage of the final run:**
  - **Source rules:** all 14 findings reviewed by hand against the code.
  - **Config rules:** all 185 findings checked automatically, with the "missing" key searched for under every
    relaxed spelling in every YAML/properties/env file of the repo. All 28 matches were reviewed: each was an
    unrelated key in another module or another library (ZooKeeper, Cassandra).
  - **Datasource check:** each of the 72 flagged modules was checked for a datasource in its own files (71
    have one).
  - **Random sample:** 12 findings, seed `20261103`, hand-verified end to end.

## Results (final run)

282 Spring Boot modules in 20 repositories, about 70 s for the whole corpus with warm clones. No crashes,
no parse errors.

| Repository | Commit | Modules | Spring Boot | Warning | Info |
|---|---|---:|---|---:|---:|
| spring-projects/spring-petclinic | 500158f732 | 1 | 4.1.0 | 2 | 2 |
| spring-petclinic/spring-petclinic-microservices | e8b747ab52 | 8 | 4.0.1 | 2 | 0 |
| spring-petclinic/spring-petclinic-rest | 5180cc9bd0 | 1 | 4.1.2 | 2 | 2 |
| gothinkster/spring-boot-realworld-example-app | ee17e31aaf | 1 | 2.6.3 | 2 | 2 |
| sqshq/piggymetrics | 6bb2cf9ddb | 9 | 2.0.3 | 0 | 0 |
| jhipster/jhipster-sample-app | 6b000b5d23 | 1 | 4.1.1 | 2 | 2 |
| microservices-patterns/ftgo-application | 558dfc53b1 | 9 | ? (Gradle) | 15 | 12 |
| macrozheng/mall | dcaa93b315 | 4 | 3.5.14 | 3 | 4 |
| elunez/eladmin | 646c58c30f | 1 | 2.7.18 | 0 | 1 |
| linlinjava/litemall | a1ef964a71 | 6 | 2.1.5 | 2 | 1 |
| yangzongzhuan/RuoYi | 9cd770d2ac | 1 | 4.1.0 | 0 | 1 |
| YunaiV/ruoyi-vue-pro | 4e0818dcd0 | 2 | ? | 0 | 1 |
| xkcoding/spring-boot-demo | 87a142f960 | 65 | 2.1.0 | 23 | 22 |
| ityouknow/spring-boot-examples | 53c8c8d6d0 | 84 | 1.3–2.x | 28 | 36 |
| jeecgboot/JeecgBoot | a4d5234755 | 12 | 2.7.18, 4.1.0 | 4 | 2 |
| paascloud/paascloud-master | 781281a950 | 10 | 1.5.13 | 0 | 0 |
| halo-dev/halo | 80b9d42c21 | 1 | ? (Gradle) | 2 | 0 |
| apache/shenyu | 1faf1221b8 | 43 | 3.3.1 | 4 | 1 |
| apache/dolphinscheduler | 282d956cbc | 6 | ? | 10 | 5 |
| thingsboard/thingsboard | e48b5780e1 | 17 | 3.5.15 | 3 | 1 |

No ERROR findings: the two ERROR rules (ST-HIKARI-003/004) need database limits from `springtune.yml`,
which a cold run does not have. They appear under *Not evaluated*, as designed.

### Precision per rule

| Rule | Findings | Repos | False positives | Notes |
|---|---:|---:|---:|---|
| ST-HIKARI-001 | 45 | 12 | 0 | |
| ST-HIKARI-002 | 45 | 12 | 0 | |
| ST-TOMCAT-001 | 25 | 7 | 0 | All INFO (both values left at library defaults); no explicit mismatch in the corpus |
| ST-TX-001 | 70 | 16 | 1 | `xkcoding/demo-codegen` opens ad-hoc JDBC connections; no Spring-managed datasource |
| ST-ASYNC-001 | 1 | 1 | 0 | litemall: `@Async` notifications on the unbounded default executor |
| ST-ASYNC-002 | 7 | 5 | 0 | One low-value: litemall's single-thread executor runs one `while(true)` task |
| ST-HTTP-001 | 6 | 5 | 0 | E.g. petclinic gateway `new RestTemplate()`, ftgo gateway. One low-value: halo's `WebClient.create(...)` UI proxy is enabled only in `application-dev.yaml` |
| ST-HTTP-002 | 0 | 0 | 0 | All earlier hits were Ribbon-era, Config-Server-configured or non-OpenFeign (see below) |
| **Total** | **199** | | **1 (0.5%)** | |

### Notable true positives

- `spring-petclinic-microservices`: `@LoadBalanced new RestTemplate()` (api-gateway) and
  `RestClient.builder().build()` (genai-service) for inter-service calls, both without timeouts.
- `jeecgboot/JeecgBoot`: `Executors.newFixedThreadPool(10)` for SSE chat streams and document vectorization,
  with unbounded queues behind a user-facing feature.
- `apache/dolphinscheduler`: all HikariCP-backed services run on default `connectionTimeout` (30 s) with
  leak detection off.

## What the first run got wrong, and the fixes

The first run (`v0`, same engine as PR #1) produced 307 findings. Diffing it against the final run and
reviewing every removed finding:

| False-positive class (v0) | Findings | Example | Fix (now) | Regression test |
|---|---:|---|---|---|
| Spring Boot 1.x defaults to tomcat-jdbc, not HikariCP | 21 | ityouknow, paascloud | HikariCP rules need Boot ≥ 2 or an explicit HikariCP dependency | `springBoot1DefaultsToTomcatJdbc` |
| Druid / dynamic-datasource owns the DataSource | 15 | macrozheng/mall, litemall, eladmin | Druid starter, `spring.datasource.druid.*`, `spring.datasource.dynamic.*` disable HikariCP rules | `druidStarterOwnsTheDataSource`, `druidOrDynamicDatasourcePropertiesWithoutStarterInBuild` |
| `@EnableAsync` without any `@Async` method | 15 | thingsboard (15 apps) | ST-ASYNC-001 requires `@Async` in reachable code | `enableAsyncWithoutAsyncMethodsIsSilent` |
| `AsyncConfigurer` in a library module was invisible | 1 | eladmin (`eladmin-common`) | **Source graph:** modules see in-repo library code through Maven/Gradle dependencies, transitively | `asyncMethodsAndExecutorsInLibraryModulesAreFollowedTransitively`, `unrelatedModulesAreNotFollowed` |
| Feign timeouts governed by Ribbon (1 s) or Config Server Hystrix (10 s) | 8 | paascloud, piggymetrics | ST-HTTP-002 silent for Ribbon-era Feign | `ribbonEraFeignIsGovernedByRibbonTimeouts` |
| OpenFeign jar present, but clients are another SDK's | 1 | apache/shenyu (`@EnableShenyuClients`) | Feign detection requires `@EnableFeignClients` | `feignArtifactWithoutEnableFeignClientsIsNotFeign` |
| **Total wrong claims** | **61** (20% of 307) | | | |

Findings v0 made that cannot be verified, and that the check now skips with an explanation:

| Unverifiable class (v0) | Findings | Example | Behavior now |
|---|---:|---|---|
| Configuration loaded from a config server | 27 | petclinic-microservices (`optional:configserver:`), piggymetrics, JeecgBoot (`nacos:`) | Rules about *missing* properties skip the module; diagnostic names the source |
| Pool built in code (custom prefix or programmatic) | 27 | `spring.datasource.primary.*` multi-datasource demos, `new HikariDataSource()` | HikariCP property rules skip the module; diagnostic says why |

Robustness fixes found by the same run:

| Problem | Example | Fix | Test |
|---|---|---|---|
| Maven resource-filter tokens (`active: @profileActive@`) made YAML unparsable (10 files) | paascloud | `@token@` is read as an unresolvable `${token}`; profile placeholders are ignored with a diagnostic | `mavenResourceFilterTokensAreUnresolvedNotParseErrors` |
| Java 22 unnamed variables (`_`) failed to parse | thingsboard | Parser level Java 25 | `java22UnnamedVariablesParse` |
| Boot ≤ 2.2 key `server.tomcat.max-threads` ignored | (preventive) | Read as fallback | `legacyTomcatMaxThreadsKeyIsRead` |
| `spring.config.name` set in code: real config files unknown | thingsboard | Missing-property rules skip the module | `relocatedConfigFilesSkipMissingPropertyRules` |
| `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` | RuoYi | HikariCP rules skip | `excludedDataSourceAutoConfigurationSkipsHikariRules` |
| ST-ASYNC-001 message wrong before Boot 2.1 (no `applicationTaskExecutor` there) | (preventive) | Message names `SimpleAsyncTaskExecutor`, thread per task | `enableAsyncBeforeBoot21DescribesSimpleAsyncTaskExecutor` |
| ST-TX-001 only ran for HikariCP | mall, RuoYi | Runs for any JDBC pool | `druidStarterOwnsTheDataSource` |

## Trade-offs and open questions

- **Recall cost of "silence over guessing".** 54 v0 findings are now *Not evaluated*, mostly in Spring
  Cloud apps whose real config lives in a config server. Next step: accept a local copy of remote config
  (`--extra-config dir/`), so teams can point the check at their config repo.
- **Demo-heavy repos dominate counts.** `xkcoding` and `ityouknow` contribute 109 of 199 findings. The
  findings are correct, but these are tutorials. Precision per repository is the same; ranking value per
  finding is future work.
- **Spring Boot version unknown for 4 builds** (not written literally in the build file the check reads). It is only used for ST-ASYNC-001
  wording and Boot 1.x detection. Resolving version catalogs and Maven properties inherited from parent POMs
  would close this.
- **No explicit Tomcat/pool mismatch in the corpus.** Every ST-TOMCAT-001 hit is the INFO default case.
  Keep INFO as the default severity.
- **Not yet measured:** suppression rate (needs users; plan §9 telemetry) and the ERROR rules, which need
  `springtune.yml` inputs. A design-partner run is the next measurement.
