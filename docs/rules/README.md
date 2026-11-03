# Rules

Every rule has a stable ID, the same in the CI check, the runtime starter and the controller.

| ID | Title | Default severity |
|---|---|---|
| [ST-ASYNC-001](ST-ASYNC-001.md) | @Async on the default unbounded executor | WARNING |
| [ST-ASYNC-002](ST-ASYNC-002.md) | Executor with unbounded queue or threads | WARNING |
| [ST-HIKARI-001](ST-HIKARI-001.md) | Connection acquire timeout too long | WARNING |
| [ST-HIKARI-002](ST-HIKARI-002.md) | Connection leak detection disabled | WARNING |
| [ST-HIKARI-003](ST-HIKARI-003.md) | maxLifetime not shorter than database idle timeout | ERROR |
| [ST-HIKARI-004](ST-HIKARI-004.md) | Connection budget exceeded | ERROR |
| [ST-HIKARI-005](ST-HIKARI-005.md) | Oversized connection pool | WARNING |
| [ST-HTTP-001](ST-HTTP-001.md) | HTTP client without timeouts | WARNING |
| [ST-HTTP-002](ST-HTTP-002.md) | Feign client on default 60 s read timeout | WARNING |
| [ST-R4J-001](ST-R4J-001.md) | Retry without exponential backoff or jitter | WARNING |
| [ST-R4J-002](ST-R4J-002.md) | Circuit breaker blind to slow calls | WARNING |
| [ST-TOMCAT-001](ST-TOMCAT-001.md) | Tomcat threads far above connection pool size | WARNING |
| [ST-TX-001](ST-TX-001.md) | No transaction or query timeout | INFO |
