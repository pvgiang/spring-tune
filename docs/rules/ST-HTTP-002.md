# ST-HTTP-002: Feign client on default 60 s read timeout

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | `@EnableFeignClients` is in the module or an in-repo library it depends on, Ribbon is not in use (Spring Cloud Hoxton and earlier route Feign through Ribbon, whose Spring Cloud default read timeout is 1 s), and the local config is complete. |
| **Options** | none. |

## What it detects

No `read-timeout` under any `spring.cloud.openfeign.client.config.<name>` or legacy `feign.client.config.<name>`.

## Why it matters

The default read timeout is 60 s. Whenever a dependency hangs, each call holds a request thread for a minute, and a few slow calls per second exhaust the pool well before the timeout fires.

## Example

Flagged:

```yaml
# nothing set
```

Fixed:

```yaml
spring.cloud.openfeign.client.config.default:
  connect-timeout: 1000
  read-timeout: 3000
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HTTP-002
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
