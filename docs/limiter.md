# Adaptive concurrency limiter (`springtune-limiter`)

An **opt-in** servlet `Filter` and WebFlux `WebFilter` that cap how many requests an instance works on at once,
and learn that cap from latency. When a downstream (database, remote service) saturates, requests over the cap
get a fast `503` + `Retry-After` instead of joining a queue that makes *every* request slow and eventually
exhausts threads, connections and memory.

It is a thin layer over Netflix [`concurrency-limits-core`](https://github.com/Netflix/concurrency-limits)
0.5.4 (Apache 2.0, one dependency: `slf4j-api`), behind our own facade (`AdaptiveLimiter`) so the dependency can
be replaced or vendored. The filters, the Spring Boot 3.5/4.x auto-configuration and the metrics are ours:
Netflix's servlet module covers neither WebFlux nor Micrometer.

**Nothing changes by default.** The limiter is not installed unless `springtune.limiter.enabled=true`, and
when it is, it runs in **shadow mode**: it counts the requests it *would* reject and lets them all through,
until you set `springtune.limiter.enforce=true`.

## Install

It is a separate artifact; the runtime starter does **not** pull it in (see [Why a separate artifact](#why-a-separate-artifact)).

```xml
<dependency>
  <groupId>io.github.winkytek.springtune</groupId>
  <artifactId>springtune-limiter</artifactId>
  <version>${springtune.version}</version>
</dependency>
```

```yaml
springtune.limiter:
  enabled: true      # shadow mode: observe only
  # enforce: true    # step 3 of the rollout below
```

At startup the mode is logged, e.g.

```
SpringTune limiter: SHADOW mode (algorithm=vegas, initial-limit=20, min-limit=10, max-limit=200, warmup-samples=500).
Nothing is rejected; would-be rejections are counted as springtune.limiter.requests{outcome=shadow_rejected}. ...
```

## How it works

Each limited request asks for a permit. Under the limit it gets one and holds it until the request ends; over
the limit it is rejected (enforce, after warmup) or let through without a permit (shadow, or during warmup).
How the request ended is fed back to the algorithm:

| Request ended | Fed back as | Why |
|---|---|---|
| Status < 500 | success (latency sample) | Includes 4xx: the server did its work |
| Status ≥ 500, or an exception | ignored (no sample) | Often a *fast* failure from a broken downstream; its latency would wrongly signal spare capacity |
| Async request timed out (`DeferredResult`/`Callable` timeout) | dropped | The clearest overload signal |
| Client went away (WebFlux cancel) | ignored | Says nothing about capacity |

Async servlet requests (`DeferredResult`, `Callable`, `WebAsyncTask`) hold their permit until the async cycle
completes, errors or times out (an `AsyncListener`), not just until the filter returns. WebFlux requests hold it
from subscription until the response completes, errors or is cancelled (`doFinally`).

**Never limited** (counted as `outcome=bypassed`):

- the actuator base path (`management.endpoints.web.base-path`, default `/actuator/**`, below
  `spring.mvc.servlet.path` if that is set), whatever `exclude-paths` says, so liveness/readiness probes never
  get a 503 and Kubernetes does not restart an instance *because* it is shedding load. With the base path at the
  root (`/`) only the health endpoint (`/health/**`, or its `path-mapping`) is excluded automatically; with
  `management.endpoint.health.probes.add-additional-paths=true`, also `/livez` and `/readyz`;
- requests on a separate management port (`management.server.port`);
- `Accept: text/event-stream` (SSE) and WebSocket upgrade requests: a stream would hold a permit for minutes
  and its "latency" is meaningless;
- paths in `exclude-paths`, or not in `include-paths`.

**Ordering.** The servlet filter runs at `Ordered.HIGHEST_PRECEDENCE + 10`: after Boot's observation filter
(`HIGHEST_PRECEDENCE + 1`), so rejections show up in `http.server.requests` (with `status=503`), and before
Spring Security (`-100`). In WebFlux, observation is done by the `HttpWebHandlerAdapter`, outside every
`WebFilter`, with the same effect. Both are verified by the load tests below; what running before Security
means is in [Spring Security in front](#spring-security-in-front).

## When it hurts

An adaptive limiter is not free. It is the wrong tool, or needs care, when:

- **Cold start and low traffic.** With few samples the algorithm has no latency baseline; it could reject on
  noise. Hence `warmup-samples` (no rejection before 500 completed requests, even in enforce mode) and
  `min-limit` (the limit never drops below 10).
- **Streaming, SSE, WebSockets, long polling.** Long-held requests distort the limit. SSE and WebSocket
  upgrades are bypassed automatically; long-polling endpoints should go into `exclude-paths`.
- **Health probes.** Covered: never limited (see above). If you serve probes outside the actuator base path,
  add them to `exclude-paths`.
- **Retries.** A 503 invites a retry. Clients that retry immediately, or several layers that each retry,
  multiply load exactly when the instance is overloaded. See [Retry budgets](#retry-budgets).
- **One limit for all endpoints.** The limit is per instance, not per endpoint. A slow endpoint raises latency,
  the limit shrinks, and fast endpoints are rejected too. If only some endpoints share the bottleneck, limit
  only those with `include-paths`.
- **The latency is not yours.** The algorithms read *latency*. If latency rises for a reason more concurrency
  does not cause (GC pauses, a slow network to the client), the limit shrinks anyway.
- **Throughput.** When the limit undershoots the real capacity, the instance serves fewer requests than it
  could. In our servlet load test enforce mode served 77–91 % of the limiter-off throughput (with `min-limit`
  lowered to 4; the default floor of 10 makes undershooting less likely). Watch it in shadow mode first.

## Defaults and why

| Default | Why |
|---|---|
| `enabled=false` | Nothing changes by adding the jar. |
| `enforce=false` (shadow) | Evidence first: you see what *would* be rejected before anything is. |
| `algorithm=vegas` | Measured, on one synthetic downstream (one endpoint, fixed capacity), so it is a reasoned default, not a proven one. Vegas compares latency with the lowest latency seen; under sustained overload its limit stayed within 7–39 in every run. Gradient2 compares short- with long-term average latency; under sustained overload the long-term average drifts up with it, and in the same test its limit swung 8–85 in enforce mode and climbed to `max-limit` (200) in shadow mode, so shadow mode would have under-reported. The price: Vegas served fewer requests (363–439 OK/s vs Gradient2's 462 in its one run), trading throughput for latency. Gradient2 and AIMD stay available. |
| `initial-limit=20`, `min-limit=10`, `max-limit=200` | A floor that keeps an instance useful while the algorithm is wrong; a ceiling below Tomcat's 200 worker threads. |
| `warmup-samples=500` | No rejection on a cold or quiet instance. |
| `exclude-paths=/actuator/**` | Probes and scraping are never limited. |
| `reject-status=503`, `retry-after=1s` | 503 is what load balancers and clients treat as "try elsewhere / later". |

## Properties

| Property | Default | |
|---|---|---|
| `springtune.limiter.enabled` | `false` | Installs the limiter |
| `springtune.limiter.enforce` | `false` | `false` = shadow: count, never reject |
| `springtune.limiter.algorithm` | `vegas` | `vegas`, `gradient2` or `aimd`. AIMD only backs off on dropped (timed-out async) requests: with synchronous endpoints it only grows. |
| `springtune.limiter.initial-limit` | `20` | Must be within [min, max] |
| `springtune.limiter.min-limit` | `10` | Applied to every algorithm (Vegas has no minimum of its own) |
| `springtune.limiter.max-limit` | `200` | |
| `springtune.limiter.warmup-samples` | `500` | Completed (successful or dropped) requests before anything is rejected |
| `springtune.limiter.include-paths` | `/**` | `PathPattern` syntax, relative to the context path |
| `springtune.limiter.exclude-paths` | `/actuator/**` | Wins over `include-paths`; the actuator base path is always excluded |
| `springtune.limiter.reject-status` | `503` | 4xx or 5xx |
| `springtune.limiter.retry-after` | `1s` | Whole seconds, rounded up; `0` omits the header |

Invalid combinations (`min-limit > max-limit`, `initial-limit` outside the range, a non-error `reject-status`)
fail at startup.

## Metrics

| Meter | Type | |
|---|---|---|
| `springtune.limiter.limit` | gauge | Current limit |
| `springtune.limiter.inflight` | gauge | Permits held right now; returns to 0 when traffic stops |
| `springtune.limiter.requests{outcome=accepted\|rejected\|shadow_rejected\|bypassed}` | counter | `shadow_rejected` = over the limit but let through (shadow mode or warmup) |

Rejected requests also appear in Boot's own `http.server.requests` with `status=503`.

## Retry budgets

A limiter sheds load only if the shed load goes away. Before enforcing:

- Clients must **back off** (exponential, with jitter) and should honour `Retry-After`.
- Retries must be **budgeted**: e.g. at most 10–20 % of requests may be retries (Resilience4j `Retry` has no
  budget of its own; gRPC/Envoy/Finagle-style retry budgets do). Unbounded retries turn every 503 into more load.
- Retry at **one layer** only. Three layers retrying 3 times each turn one rejection into up to 27 requests.
- A load balancer that retries 503s on another instance spreads the load; one that retries on the same
  instance does not help.

SpringTune's `check` rule [ST-R4J-001](rules/ST-R4J-001.md) flags Resilience4j retries without exponential back-off or jitter.

## Rollout: shadow → enforce

1. **Shadow.** `enabled=true`. Deploy. Watch `springtune.limiter.limit`, `inflight` and
   `requests{outcome=shadow_rejected}` through normal traffic *and* a peak (an upper bound, see [Measured](#measured)). Shadow rejections at normal traffic
   mean the limit is too low for this service: raise `min-limit`, narrow `include-paths`, or stop here.
2. **Check the clients.** Back-off, retry budget, one retrying layer (above). Make sure your dashboards and
   alerts treat a rise in 503s from this instance as "shedding", not as an outage.
3. **Enforce on one instance** (`enforce=true`), or a canary. Compare its p99 and error rate with the others
   during the next peak.
4. **Enforce everywhere.** Keep `shadow_rejected`/`rejected` on a dashboard; a sustained rejection rate means
   the service needs capacity, not a limiter.

Back out at any time with `enforce=false` (shadow) or `enabled=false` (removed).

## Measured

`ServletOverloadTest` and `ReactiveOverloadTest` (in this module, run on every build) use a real server, the
real Netflix limiter and real `java.net.http` clients. The fake downstream serves 10 calls at a time, 20 ms each
(a fair `Semaphore`), so its capacity is 500 requests/s and latency grows linearly with the queue. Closed-loop
callers send a request, wait for the answer and send the next; after a 503 they wait 25 ms. Each scenario gets
1.5 s of unmeasured warm-up (JIT, connections, warmup-samples=50), then 4 s (servlet) or 3 s (WebFlux) are
measured. A separate client calls `/actuator/health` every 100 ms throughout. `min-limit` is lowered to 4 so the
limit is the algorithm's own, not the floor (which here would equal the downstream's capacity). Server-side
latency is Boot's `http.server.requests` timer for successful requests.

Machine: 4 vCPU container, JDK 21, Boot 4.1.2 unless noted. Each pair of lines is one build; three builds on Boot 4.1.2,
two on 3.5.16.

**Servlet (Tomcat), 160 callers, Vegas**

| Run | Mode | OK/s | Server p50 / p99 | Client p99 | 503s (all with `Retry-After`) | Limit during run | Health |
|---|---|---|---|---|---|---|---|
| 1 | off | 458 | 334 / 435 ms | 427 ms | 0 | – | 33/33 × 200 |
| 1 | enforce | 367 | 37 / 83 ms | 138 ms | 11 505 | 7–34 | 27/27 × 200 |
| 2 | off | 485 | 334 / 351 ms | 344 ms | 0 | – | 38/38 × 200 |
| 2 | enforce | 439 | 37 / 70 ms | 86 ms | 14 658 | 9–32 | 33/33 × 200 |
| 3 | off | 473 | 334 / 368 ms | 367 ms | 0 | – | 36/36 × 200 |
| 3 | enforce | 363 | 37 / 108 ms | 156 ms | 9 559 | 7–29 | 28/28 × 200 |
| Boot 3.5.16 | off | 483 | 334 / 351 ms | 342 ms | 0 | – | 38/38 × 200 |
| Boot 3.5.16 | enforce | 439 | 37 / 70 ms | 81 ms | 15 157 | 9–30 | 34/34 × 200 |
| Boot 3.5.16 (2) | off | 486 | 334 / 351 ms | 342 ms | 0 | – | 38/38 × 200 |
| Boot 3.5.16 (2) | enforce | 434 | 37 / 66 ms | 84 ms | 14 730 | 8–31 | 33/33 × 200 |

**WebFlux (Netty), 80 callers, Vegas**

| Run | Mode | OK/s | Server p50 / p99 | Client p99 | 503s | Limit during run | Health |
|---|---|---|---|---|---|---|---|
| 1 | off | 464 | 167 / 242 ms | 243 ms | 0 | – | 13/13 × 200 |
| 1 | enforce | 410 | 35 / 66 ms | 142 ms | 3 437 | 8–29 | 20/20 × 200 |
| 2 | off | 432 | 167 / 267 ms | 701 ms | 0 | – | 13/13 × 200 |
| 2 | enforce | 412 | 37 / 87 ms | 138 ms | 3 548 | 8–39 | 19/19 × 200 |
| 3 | off | 476 | 167 / 234 ms | 242 ms | 0 | – | 12/12 × 200 |
| 3 | enforce | 452 | 37 / 70 ms | 76 ms | 5 730 | 8–33 | 25/25 × 200 |
| Boot 3.5.16 | off | 473 | 167 / 242 ms | 240 ms | 0 | – | 13/13 × 200 |
| Boot 3.5.16 | enforce | 400 | 37 / 83 ms | 205 ms | 3 228 | 7–29 | 20/20 × 200 |
| Boot 3.5.16 (2) | off | 450 | 167 / 251 ms | 247 ms | 0 | – | 14/14 × 200 |
| Boot 3.5.16 (2) | enforce | 446 | 37 / 64 ms | 82 ms | 5 159 | 8–29 | 25/25 × 200 |

**Shadow mode** (servlet, 160 callers, 3 s, no warm-up phase): zero 503s in every run, 1 285–1 470
`shadow_rejected`, latency the same as with the limiter off (it changes nothing). Note that the shadow limit
sank to 6–7 while enforce mode held 7–39: in shadow mode the admitted requests still queue behind all the
others, so the algorithm sees worse latency than it would if it were enforcing. **Shadow mode over-reports**
what enforce mode would reject; treat `shadow_rejected` as an upper bound.

**Gradient2, same servlet test** (one run, for the choice of default): enforce served 462 OK/s at server p99
184 ms (Vegas: 70–108 ms), its limit swung 8–85; in shadow mode it climbed to 200.

What this shows: with the limiter, accepted requests took 64–108 ms at p99 on the server instead of 234–435 ms,
the excess got an immediate 503 with `Retry-After`, `/actuator/health` answered 200 throughout, and no permit
leaked (`inflight` returned to 0 after every run; the tests assert it). The price was 1–23 % fewer successful
requests per second, because the limit sometimes undershoots the downstream's capacity of 10. Client-side p99
is higher than server-side because 160 client threads and the server share 4 vCPUs.

What it does not show: real networks, real databases, many instances behind a load balancer, or traffic that
changes shape. These are in-JVM tests on one machine.

## Limitations

- **Only what passes the filter is protected.** The limit applies once a request reaches the filter. Tomcat's
  accept queue, connection limit (`server.tomcat.max-connections`) and worker threads (`threads.max`) come
  first: if all worker threads are blocked (e.g. by bypassed or excluded endpoints, or by work done before the
  filter), new requests wait in Tomcat's queue and the limiter never sees them. Keep `max-limit` below
  `server.tomcat.threads.max`.
- **Virtual threads: tested on Tomcat, servlet stack only.** With `spring.threads.virtual.enabled=true` there is
  no worker-thread ceiling, so the limiter is the only concurrency cap. `ServletVirtualThreadsOverloadTest`
  (Java 21+, skipped on older JREs) runs 240 callers (more than Tomcat's default 200 workers) with
  `max-limit=60`: with the limiter off more than 200 requests are in the handler at once (all on virtual
  threads), so the ceiling really is gone; in enforce mode, after warm-up, the requests in the handler at once
  and the limiter's `inflight` never exceed `max-limit`, nothing passes without a permit, every 503 has
  `Retry-After`, health answers 200 and `inflight` returns to 0. Latency is printed, not asserted (see below).
  **During warm-up**
  (`warmup-samples`) nothing is rejected, so a cold instance hit by a burst has no cap at all on virtual
  threads (`max-limit` does not apply either); keep `warmup-samples` small. **One CPU:** pinned to a single CPU
  (`taskset -c 0`, server and 240 client threads together), enforce mode on virtual threads served only 20
  requests in 4 s (server-side p50 805 ms) while rejecting ~900/s, although the same pinned run with the limiter
  off served ~280/s and the platform-thread test passed pinned. Our reading, not proven: the server has one
  carrier thread per CPU, which competes with every other runnable thread for CPU, and that share goes to cheap
  rejections. The test therefore needs ≥ 2 CPUs (it is skipped otherwise). On virtual threads it asserts the cap
  (counts) and only reports latency: enforce p99 measured 91–385 ms over 14 runs (off 535–636 ms) on a shared
  4 vCPU machine, against 62–108 ms with platform threads, too wide a spread for a CI gate. Not tested: WebFlux with virtual threads,
  Jetty/Undertow, pinning-heavy code (`synchronized` around blocking I/O on Java 21–23).
- **Separate management port:** bypassed by port (the filter compares the request's local port with
  `local.management.port`); unit-tested, not exercised end-to-end. (Boot's separate management server is a
  child context that, as far as we know, does not apply the main context's filters anyway; the port check is a
  second guard.)
- **Servlet client disconnects** are seen only when writing the response fails (then: ignored). A request
  whose client left keeps its permit until the handler returns.
- **Per-instance, global limit**: no per-endpoint or per-tenant partitions (Netflix's partitioned limiter is not
  exposed yet).
- **AIMD** reacts only to dropped (timed-out) requests and the internal 5 s timeout; for synchronous endpoints it
  only grows. Use Vegas or Gradient2.
- **The bypass rules trust request headers.** Any request with `Accept: ...text/event-stream...` or
  `Upgrade: websocket` is bypassed, whatever endpoint it reaches and whether or not it streams. Ordinary clients
  do not send these to plain endpoints (some, e.g. MCP clients, send `Accept: application/json,
  text/event-stream` on every call), but a client that wants to skip the limit can. The limiter sheds overload; it
  is not an abuse control.
- The limiter runs **before Spring Security** (see [Spring Security in front](#spring-security-in-front)):
  unauthenticated requests take permits too, and get 503 rather than 401 while the limit is full.

## Spring Security in front

The servlet filter runs at `HIGHEST_PRECEDENCE + 10`, Spring Security's `FilterChainProxy` at `-100` (WebFlux:
the limiter `WebFilter` also runs before `WebFilterChainProxy`). So the limiter decides **before**
authentication:

- **Every request takes a permit, authenticated or not.** A request without (valid) credentials that arrives
  while the limit is full gets `503` + `Retry-After`, not `401`. Clients must not read a 503 as "credentials
  accepted".
- **A 401 is a successful sample.** Status < 500 counts as success, so fast 401s feed low latencies into the
  algorithm. A flood of unauthenticated requests both uses permits and shapes the limit; the limiter is
  overload protection, not abuse control (rate-limit or block such traffic in front of the instance).
- **Authentication runs inside the permit, so its cost is part of the latency the limit learns from.**
  Requests rejected by the limiter are never authenticated, which protects the CPU from authentication work
  under overload, but only after warm-up: before `warmup-samples` requests completed, nothing is rejected.
  Observed once while writing the test (4 vCPU shared with other builds): with `InMemoryUserDetailsManager`
  Spring Security upgrades a `{noop}` password to BCrypt after the first login, every request then spent tens of
  ms of CPU in BCrypt, the 50-sample warm-up did not finish under 160 callers, and `/work` took 12–15 s on the
  server. Cheap authentication (tokens, cached sessions) or a short warm-up avoids this; it is not an assertion
  of any test.

`LimiterWithSpringSecurityTest` (Tomcat, HTTP Basic, enforce, 160 callers of which 16 send no credentials,
plus a health poller) asserts: authenticated callers get only 200 or 503, unauthenticated ones only 401 or 503
(never a 500 from the filters' interaction), unauthenticated requests are rejected with 503 too, every 503 has
`Retry-After`, `/actuator/health` (permitted) answers 200 throughout, every `/work` request (with or without
credentials) was counted by the limiter, and `inflight` returns to 0: a request Security answers with 401
releases its permit. It runs in its own surefire execution (Spring Security on the classpath would secure the
other test applications of the module). The same ordering on WebFlux is not load-tested with Security.

## Why a separate artifact

The runtime starter is read-only (trust levels 1–2: observe, advise). A limiter *rejects requests*: putting it
in the starter would make "add the starter" mean "add a component that can return 503s", even if disabled by
default, and would add Netflix `concurrency-limits-core` to every starter user's classpath. Teams opt in by
adding `springtune-limiter`; its auto-configuration (`LimiterAutoConfiguration`) is in the same jar, so there is
no extra starter.

## How it was verified

| Check | Test |
|---|---|
| Outcome mapping (< 500 success, 5xx ignore, dropped), idempotent release, warmup rule (5xx does not count), shadow never rejects, [min, max] clamp for all three algorithms, fail-fast validation | `AdaptiveLimiterTest` (real Netflix `SimpleLimiter`) |
| Paths, actuator base path (also below the servlet path and at the root), `/livez`/`/readyz`, SSE, WebSocket, management port | `RequestPolicyTest` |
| Actuator below `spring.mvc.servlet.path` and additional probe paths bypassed end to end; `..` through the actuator path reaches no handler | `ServletPathBypassTest` (Tomcat, raw sockets) |
| Off by default; shadow by default when enabled; mode logged; servlet vs reactive wiring; no servlet API needed for reactive; not installed without the Netflix jar or outside web apps; works without Micrometer; `min > max`, `min-limit=0`, initial outside [min, max] and negative `retry-after` fail; meters | `LimiterAutoConfigurationTest` |
| `DeferredResult` holds its permit until completed and releases it; timeout releases as dropped; `Callable`; exceptions (sync and `setErrorResult`) release without a sample; rejection format (503, `Retry-After: 1`, text body); health and SSE served while the only permit is taken; WebSocket upgrade bypassed | `ServletAsyncAndBypassTest` (Tomcat, limit pinned to 1) |
| Overload, off vs enforce vs shadow (numbers above); rejections in `http.server.requests`; no leaked permits | `ServletOverloadTest` |
| Same on Netty; client cancellation releases the permit without a sample; SSE and actuator bypassed | `ReactiveOverloadTest` |
| Virtual threads (`spring.threads.virtual.enabled=true`, Java 21+), 240 callers, `max-limit=60`: off → more than 200 in the handler at once, all on virtual threads; enforce → in-handler concurrency and `inflight` ≤ `max-limit` after warm-up, no request without a permit, 503s with `Retry-After` and in `http.server.requests`, health 200, inflight back to 0; server p50/p99 printed, not asserted; skipped below 2 CPUs | `ServletVirtualThreadsOverloadTest` |
| HTTP Basic (Spring Security) behind the limiter, enforce, 10% unauthenticated callers: only 200/401/503, 503s also for unauthenticated requests, all with `Retry-After`, every request counted by the limiter, health 200, no leaked permit | `LimiterWithSpringSecurityTest` (own surefire execution) |
| Spring Boot 4.1.2 and 3.5.16 | same tests, `-Dspring-boot.version=3.5.16` |
