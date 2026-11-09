# Launch variants (drafts; posting is a human step)

The source of truth is [launch-post.md](launch-post.md); every number here is in it and traced in
[claims.md](claims.md). If you edit a number here, edit it there and in claims.md too, then run
`scripts/content/check-claims.sh`. Post only after [checklist.md](checklist.md) is signed off.

Order: r/java and Show HN on different days (each needs the author answering comments for the first few hours),
dev.to as the long-form copy, a JUG talk or newsletter after the first feedback. Record each in the launch log in
[docs/adoption/gates.md](../adoption/gates.md#launch-log).

---

## r/java

r/java removes help requests and low-effort promotion; a technical write-up with data and an honest limits section
is fine. Post as a link post to the GitHub-rendered launch post, or as a text post with the body below. Do not
cross-post to r/programming the same day.

**Title**

> I ran a static config check over 20 popular Spring Boot projects. Every HikariCP module it could evaluate waits more than 5 s for a connection, and 6 HTTP clients have no timeouts

**Body**

```markdown
I build SpringTune, an open-source (Apache 2.0) check for the Spring Boot configuration behind a common outage
pattern: the database or a downstream service gets slow, and a default nobody chose turns "slow" into "down".

To see whether it finds anything real, I ran it over 20 well-known public Spring Boot repositories (Spring
PetClinic and its microservices variant, JHipster's sample app, Apache DolphinScheduler, Apache ShenYu,
ThingsBoard, JeecgBoot, mall and others; 282 modules) and reviewed the findings myself:

- All 45 HikariCP modules it could evaluate, in 12 of the 20 repos, wait more than 5 s for a connection when the
  pool is exhausted (44 of them up to 30 s; 38 just by not setting `connection-timeout`), and none has leak
  detection on. 25 of the 45 are in two tutorial collections.
- 6 HTTP clients built without timeouts in 5 repos, including the PetClinic microservices gateway's
  `@LoadBalanced new RestTemplate()`.
- 7 executors with unbounded queues in 5 repos, e.g. JeecgBoot's AI chat streams on `newFixedThreadPool(10)`.
- 199 findings, 1 false positive (0.5%) in my review. The first run had at least 61 wrong findings out of 307
  (Boot 1.x on tomcat-jdbc, Druid-owned datasources, config in a Config Server), so I fixed the tool and made each
  pattern a regression test. When the config isn't in the repo it reports "not evaluated, and why" instead of
  guessing.

Raw JSON for every repo, pinned commits and the method are in the repository; the numbers were re-run with the
released 0.4.0 CLI and came out identical.

It runs as a GitHub Action (SARIF, line-level annotations), a Maven plugin, a Gradle plugin or a CLI, all 0.4.0 on
Maven Central under `io.github.winkytek.springtune`. Static only: nothing is built or executed.

What it is not: there is a runtime starter with read-only advice, and an experimental, opt-in apply mode that
meets 5 of the 6 bars I set before writing its load tests. The missing one is a production user: nobody runs it
in production that I know of, so please don't treat it as more than an experiment.

Write-up with the code, the failure arithmetic and the fixes:
https://github.com/pvgiang/spring-tune/blob/main/docs/launch/launch-post.md

I'd especially like false positives and rule requests; every false positive becomes a regression test.
```

---

## Show HN

HN norms: a plain title with no superlatives, the maker posting, something people can try right away, and the
author in the comments. The URL field takes the repository; the text goes in the first comment (or the text
field, if posting without a URL). HN renders plain text only: no Markdown headers or bold, and lines indented by
two spaces become code.

**Title** (80 characters max)

> Show HN: SpringTune – a CI check for Spring Boot connection pool and timeout config

**URL**

> https://github.com/pvgiang/spring-tune

**Text / first comment**

```text
Hi HN, I built SpringTune, a static check for one class of Spring Boot configuration: connection pools,
HTTP client timeouts, executor queues and retries, the settings that turn a slow dependency into an outage.

The motivating case: HikariCP waits up to 30 s for a connection by default, and Spring Boot runs 200 request
threads against 10 connections. If the database slows from 30 ms to 3 s per query, all 200 threads are blocked
in about 4 seconds, health checks included (back-of-envelope arithmetic, not a measurement).

To check it finds real things, I ran it over 20 public Spring Boot repositories (282 modules) and reviewed the
results: every HikariCP module it could evaluate (45, in 12 repos) waits more than 5 s for a connection, 44 of
them up to 30 s; 6 HTTP clients in 5 repos have no timeouts, including the Spring PetClinic microservices gateway.
199 findings, 1 false positive in my review. The first run was much worse (at least 61 of 307 wrong), which is
why it now says "not evaluated" when the config lives in a Config Server instead of guessing. Raw reports and
pinned commits are in the repo.

Try it: GitHub Action, Maven plugin, Gradle plugin or CLI, 0.4.0 on Maven Central. It is static only; nothing
is built or executed and nothing is sent anywhere (there is no telemetry).

  java -jar springtune-check-cli-0.4.0-all.jar check . --fail-on WARNING

Limits: 13 rules, one topic. Config outside the repository is invisible to it. There is also a runtime
starter with read-only advice and an experimental, opt-in mode that applies one pool change and reverts it if
things get worse; it meets 5 of the 6 bars I wrote down before building its load tests, and the sixth is a
production user, which it doesn't have.

I'd like to hear about false positives and which rule you'd want next.
```

---

## dev.to

Paste the body of [launch-post.md](launch-post.md) below this front matter, without its leading HTML comment.
Keep `published: false` until the checklist is signed off; set `canonical_url` so search engines credit the
repository copy. dev.to allows at most 4 tags, lowercase alphanumeric.

```yaml
---
title: "We scanned 20 popular Spring Boot projects for the config behind a common outage"
published: false
description: "Every HikariCP module we could evaluate waits more than 5 s for a connection, 6 HTTP clients have no timeouts, and 1 false positive in 199 findings. Method, raw data and a CI check you can run on 0.4.0."
tags: java, springboot, devops, opensource
canonical_url: https://github.com/pvgiang/spring-tune/blob/main/docs/launch/launch-post.md
---
```
