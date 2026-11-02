# ST-HTTP-001: HTTP client without timeouts

| | |
|---|---|
| **Severity** | WARNING |
| **Applies when** | Any module with Java sources. |
| **Options** | none. |

## What it detects

`new RestTemplate()`; `RestClient.create()` / `create("url")` / `create(URI.create(...))`; `RestClient.builder()…build()` without `requestFactory`/`apply`; `WebClient.create(...)`; `WebClient.builder()…build()` without `clientConnector`/`apply`/`exchangeFunction`; `new RestTemplateBuilder()…build()` without timeout or request-factory calls. **Not** reported: injected Boot-managed builders (their timeouts may come from `spring.http.client(s).*` properties) or builders stored in variables. This avoids false positives at the cost of some missed cases.

## Why it matters

A call without a read timeout waits as long as the remote side keeps the socket open. When a dependency hangs, request threads pile up on it until the service stops responding, so one slow dependency becomes an outage.

## Example

Flagged:

```java
RestTemplate rest = new RestTemplate();
```

Fixed:

```java
RestTemplate rest = builder.connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(3)).build();
```

## Suppress

```yaml
# springtune.yml
suppress:
  - rule: ST-HTTP-001
    module: my-service        # optional
    reason: "Why this is acceptable here"   # required
```
