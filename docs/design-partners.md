# First staging run: what we ask, what you will see

SpringTune has **no users yet** (zero teams, zero feedback reports). This page is for the first teams willing to run
the starter in staging and tell us what it recommended, including when it was wrong.

## What we ask

- **Two weeks in `advise` mode in staging** (the default mode: read-only, nothing is changed).
- **About 30 minutes of setup**: add the dependency, expose the endpoint, optionally set a connection budget.
- **One feedback issue per recommendation**, including the ones you reject. A rejection with a reason is as useful as
  an acceptance.
- **A short note on day 14**: what you looked at, what you would change.

## Setup

```xml
<dependency>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-spring-boot-starter</artifactId>
  <version>0.4.1</version>
</dependency>
```

0.4.1 is tagged and released on GitHub; its Maven Central publication is pending. Until
`https://repo1.maven.org/maven2/io/github/winkytek/springtune/springtune-spring-boot-starter/0.4.1/` answers, use
`0.4.0`. The `evaluations` status list and `GET /actuator/tuning/report` described below arrive in **the release after
0.4.1**; on 0.4.1 the feedback form accepts the plain `GET /actuator/tuning` output (see *How to report*).

```yaml
management:
  endpoints.web.exposure.include: tuning   # secure /actuator/** as you do for other actuator endpoints
springtune:
  budget:                                  # optional in advise mode; caps raise recommendations
    database-max-connections: 100          # what this service may use on the database
    max-replicas: 6                        # largest number of pods that can run at once (HPA max + surge)
```

Add `spring-boot-starter-actuator` if it is not there yet: without the HikariCP Micrometer timers the pool-raise
rule `ST-REC-HIKARI-001` cannot fire (it shows `NO_SIGNAL`). Details: [starter](starter.md).

## What you will see, and when

With the defaults (`springtune.sampling.interval=10s`, 5-minute windows, a 24-hour window for lowering a pool), from
the first sample of each pool or executor:

| When | `GET /actuator/tuning` |
|---|---|
| First 10 s | `evaluations: []`, `recommendations: []` |
| Up to ~5 min | every rule `COLLECTING`, with samples and seconds seen vs needed |
| After 5 min | raise/hold rules (`ST-REC-HIKARI-001`, `-002`, `-004`, `ST-REC-EXEC-001`) `QUIET` (healthy), `RECOMMENDING`, or `NO_SIGNAL` (a meter is missing, named); the lower-the-pool rule `ST-REC-HIKARI-003` still `COLLECTING` |
| After 24 h | `ST-REC-HIKARI-003` also `QUIET` or `RECOMMENDING` |

A healthy staging service will often show **no recommendation at all** for two weeks, with every rule `QUIET`.
That is a result worth reporting too. Statuses are explained in [starter: evaluation status](starter.md#evaluation-status-is-an-empty-recommendations-healthy-after-041).

## How to report

1. `GET /actuator/tuning/report` (release after 0.4.1). It is built from an allowlist: rule ids, enums, numbers,
   instants, setting names and pseudonyms such as `hikari#1`; pool and bean names, principals, reasons and exception
   texts are never copied ([what is kept and dropped](starter.md#shareable-report-get-actuatortuningreport-after-041)).
   Read it before you paste it.
2. Each recommendation in it has a `feedbackUrl`: open it, and the
   [recommendation feedback form](https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml)
   opens with the title, SpringTune version, Spring Boot version, recommended value and the entry itself filled in.
   Choose the outcome (applied, applied with a different value, agreed but not applied, rejected) and say why.
3. On 0.4.1: open the form directly and paste the `recommendations[]` entry from `GET /actuator/tuning`, after
   replacing pool names, hostnames and anything confidential yourself; that output is **not** allowlisted.

## What never leaves your system

SpringTune sends nothing anywhere: no telemetry, no phone-home, no background upload. The endpoint answers only the
requests you make; a report reaches us only if you paste it into an issue.

## How we count

Your reports feed gate 2 of the plan ("recommendations are trusted": accepted / all reports, judged only from 10+
reports by 3+ teams). The exact queries are in [adoption gates](adoption/gates.md); we do not count anything else.

## How to stop

`springtune.enabled=false` removes every SpringTune bean; or remove the dependency.

## Current state, honestly

- Zero users and zero feedback reports so far.
- `apply` mode (one reviewed change, verified and reverted automatically on regression) is **experimental**: it meets
  5 of the 6 pre-registered bars for leaving experimental; the missing one is a production user
  ([iteration 4 brief, §2](delivery/iteration-4-brief.md#2-pre-registered-bar-for-leaving-experimental-posre-recorded-before-any-load-test-code)).
  We do not ask partners to use it.
- Recommendations have been checked against synthetic signals, H2 and a real PostgreSQL load suite, not against a
  production workload. Whether they are right for yours is exactly what we want to learn.
