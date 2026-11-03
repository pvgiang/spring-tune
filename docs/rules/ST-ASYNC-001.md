# ST-ASYNC-001: @Async on the default unbounded executor

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | `@EnableAsync` appears in `src/main/java`, virtual threads are off, no `@Bean` returns an executor type, and no class implements `AsyncConfigurer`. |
| **Options** | none. |

## What it detects

`spring.task.execution.pool.queue-capacity` is not set, so Spring Boot's `applicationTaskExecutor` has an unbounded queue (8 core threads; `max-size` never takes effect).

## Why it matters

With an unbounded queue the pool never grows past its core size. Under load, async work queues up without limit: latency grows, memory fills, and queued tasks are lost on restart. Note: since Spring Boot 2.1, `@Async` does not fall back to `SimpleAsyncTaskExecutor` by default; the unbounded queue is the real risk.

## Example

Flagged:

```java
@EnableAsync
@SpringBootApplication
class App {}
```

Fixed:

```yaml
spring:
  task:
    execution:
      pool:
        core-size: 8
        max-size: 32
        queue-capacity: 500
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-ASYNC-001
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
