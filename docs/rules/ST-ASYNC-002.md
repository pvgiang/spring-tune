# ST-ASYNC-002: Executor with unbounded queue or threads

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | Any module with Java sources. |
| **Options** | none. |

## What it detects

`new ThreadPoolTaskExecutor()` whose enclosing method never calls `setQueueCapacity`; `Executors.newFixedThreadPool` / `newSingleThreadExecutor` (unbounded queue); `Executors.newCachedThreadPool` (unbounded threads); `new ThreadPoolExecutor(..., new LinkedBlockingQueue<>())` without capacity.

## Why it matters

Without a bound, a slow downstream turns into unlimited queued work (memory, latency, lost tasks) or unlimited threads (CPU and memory exhaustion). Nothing pushes back on the caller.

## Example

Flagged:

```java
ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
executor.setMaxPoolSize(16); // ignored: queue is Integer.MAX_VALUE
```

Fixed:

```java
executor.setQueueCapacity(200);
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-ASYNC-002
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
