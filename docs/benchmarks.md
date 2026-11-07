# Overhead benchmarks (JMH)

What the runtime starter and the limiter cost, measured with [JMH](https://github.com/openjdk/jmh) 1.37. Plan §20
asks for published numbers; the gates were pre-registered in the
[iteration-4 brief](delivery/iteration-4-brief.md) (row 14) before any benchmark code was written.

| Gate (pre-registered) | Measured (mean ± 99.9 % error) | Verdict |
|---|---|---|
| Sampler tick with 4 components **< 1 ms** | **376 ± 17 µs** (re-measured 2026-11-06 after a setup fix, see below) | **Pass** (upper bound 393 µs) |
| Limiter filter adds **< 10 µs** per request, 1 thread | **0.38 ± 0.02 µs** (shadow), **0.39 ± 0.02 µs** (enforce) | **Pass** |
| Limiter filter adds **< 10 µs** per request, 8 threads | **4.6 ± 3.3 µs** (shadow), **4.6 ± 1.0 µs** (enforce) | **Pass**, with less margin (upper bound 7.9 µs); see [contention](#contention-in-the-limiter) |

## Machine

These are one machine's numbers, not a property of the code. Rerun on yours before relying on them.

| | |
|---|---|
| Date | 2026-11-05 |
| CPU | 4 vCPU, Intel Xeon @ 2.10 GHz (Firecracker VM, shared host) |
| Memory | 15 GB |
| JVM | OpenJDK 21.0.12 (Ubuntu build), default flags, default GC (G1) |
| Spring Boot | 4.1.2 (HikariCP, Micrometer, Spring Framework from its BOM) |
| SpringTune | 0.3.0-SNAPSHOT, commit `d1df94b` + this change |
| JMH | `-f 2 -wi 3 -i 5 -prof gc`, 2 s per iteration: 2 forks × 5 measured iterations = 10 samples per row |

Raw JMH output: [2026-11-05-sandbox-4vcpu.json](benchmarks/2026-11-05-sandbox-4vcpu.json) (limiter filter; its sampler
rows predate the setup fix below), for the extra 4-thread filter run
[2026-11-05-sandbox-4vcpu-filter-4threads.json](benchmarks/2026-11-05-sandbox-4vcpu-filter-4threads.json), and for the
sampler tick [2026-11-06-sandbox-4vcpu-sampler.json](benchmarks/2026-11-06-sandbox-4vcpu-sampler.json) (same machine,
same options).

## Sampler tick (`SamplerTickBenchmark`)

One call of `TuningService.sample()` in `advise` mode (the default): sample every component, then recompute the
recommendations. The starter's scheduler runs it once per `springtune.sampling.interval` (10 s by default) on one
thread, never on a request thread.

Components are real: H2 in-memory `HikariDataSource`s with Micrometer metrics (wired as Boot wires them) and
initialized `ThreadPoolTaskExecutor`s, in a bean factory with a `SimpleMeterRegistry`. Measured in **steady state**:
setup first runs enough ticks (on a fake clock that advances 10 s per tick) to fill Hikari's longest retention
window, the 24 h low-utilization window, i.e. 8,641 samples per pool. Each measured tick adds one sample, evicts one
and scans full windows. A freshly started application is cheaper.

| Components | Time per tick | Allocated per tick |
|---|---|---|
| 1 (one Hikari pool) | 170 ± 6 µs | 115 KB |
| 4 (two Hikari pools, two executors) | **376 ± 17 µs** | 232 KB |

**Setup fix (2026-11-06).** The first version of the benchmark filled the windows without advancing the clock, so all
8,651 setup samples had the same timestamp: the 24 h window never counted as covered and the deque held up to twice
the samples until about 8,641 ticks into the run evicted them all at once. A run without warm-up showed it (iterations
of 1 s: 226, 470, 600, 596, 386, 354 µs). With the default 3 × 2 s warm-up that happened before measurement; the
first published numbers (191 ± 22 and 479 ± 45 µs, in the 2026-11-05 JSON) were therefore also steady state. We
have not explained why they are higher than the rerun (the host is a shared VM); the gate verdict is the same either
way. Setup now steps the clock per sample.

At the default 10 s interval, 376 µs is about 0.004 % of one core. The cost is dominated by the Hikari component's
24 h window: every tick copies the samples covering it into a new list (hence ~115 KB per pool per tick), so it grows
with `low-utilization-window / sampling.interval`. A 1 s interval with the default 24 h window would retain 86,401
samples per pool and cost roughly ten times more per tick, ten times as often; that configuration was not measured.

## Limiter filter (`LimiterFilterBenchmark`)

One limited request through `ConcurrencyLimitFilter` with default settings (Vegas, initial limit 20, min 10): path
policy, permit acquire, the chain, release with the response status (which feeds the Vegas algorithm). `none` calls
the same terminal chain without the filter, so the filter's cost is `shadow − none` or `enforce − none` (`none` is
about 1–3 ns, so the filter rows are the cost). The chain does no work and request/response objects are reused per
thread (Tomcat recycles its own): these numbers are the filter alone, not a servlet container. With at most 8
requests in flight the limit (≥ 10) is never reached, so shadow and enforce take the same path and nothing is
rejected (the benchmark fails if anything is): this is the cost every accepted request pays, not the cost of a
rejection.

| Threads | none | shadow | enforce | Allocated per request (filter) |
|---|---|---|---|---|
| 1 | 1.2 ± 0.03 ns | **384 ± 16 ns** | **393 ± 20 ns** | 608 B |
| 4 (extra run) | 1.5 ± 0.6 ns | 2.58 ± 0.24 µs | 2.49 ± 0.84 µs | not measured |
| 8 | 3.2 ± 1.5 ns | **4.61 ± 3.34 µs** | **4.56 ± 0.97 µs** | 608–639 B |

### Contention in the limiter

The per-request cost grows from ~0.4 µs on one thread to ~2.5 µs on 4 threads and ~4.6 µs on 8: the threads
contend on the limiter's shared state (the in-flight counter and the limit algorithm's per-sample update; we have not
profiled which dominates). Three things make this a worst case:

- The chain does nothing, so threads hit the limiter back to back. A real request spends micro- to milliseconds in
  the handler between acquire and release, so collisions are far rarer.
- 8 threads on 4 vCPUs: JMH's average time per operation then includes time a thread waits for a CPU, about doubling
  the score compared with the work done.
- The shared VM host made the 8-thread shadow row noisy (± 3.3 µs).

It still passes the gate, but with a margin of about 2× rather than the 25× of the single-thread case. If it matters
for your service, measure with your handler in place.

## Rerun

```bash
mvn -B -ntp -Pbenchmarks package -DskipTests -Dinvoker.skip -pl springtune-benchmarks -am
java -jar springtune-benchmarks/target/benchmarks.jar -f 2 -wi 3 -i 5 -prof gc -rf json -rff jmh-result.json
# one benchmark, other thread count:
java -jar springtune-benchmarks/target/benchmarks.jar LimiterFilterBenchmark.request_1thread -t 4
```

A full run takes about 5 minutes. Or run *Actions → Benchmarks → Run workflow*
([`benchmarks.yml`](../.github/workflows/benchmarks.yml), manual only, 40-minute timeout): it uploads the JMH JSON
and writes the table and the runner's CPU to the job summary. Shared GitHub runners are noisy (2–4 vCPUs, neighbours),
so treat their numbers as indicative and compare runs on the same machine only.

The `springtune-benchmarks` module is built only with `-Pbenchmarks`, and is never installed or deployed. JMH is
licensed GPLv2 with the Classpath Exception; it is a development-time tool here and no published artifact contains
or depends on it.
