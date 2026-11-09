# HTTP calls that never give up: timeouts on `RestTemplate`, `RestClient` and `WebClient`

*Deep dive #2 · rule [ST-HTTP-001](../rules/ST-HTTP-001.md) · SpringTune 0.4.0 · numbers traced in
[docs/launch/claims.md](../launch/claims.md)*

`new RestTemplate()` has no connect timeout and no read timeout. Neither does a `RestClient` or `WebClient` built
without a configured connector. A call made with one of them waits as long as the other side keeps the socket
open, and "the other side" is not always a process that is still alive. One hung dependency can then hold every
thread that calls it.

## How it goes wrong

The story below is a composite of a common incident pattern, not a report about any real company. The numbers are
illustrative.

An API gateway forwards requests to a `customers` service through a load-balanced `RestTemplate` built with
`new RestTemplate()`. It runs on 200 Tomcat threads and handles 100 requests per second; a call to `customers`
normally takes 20 ms, so about 2 threads are busy at any time.

At 03:10 the node running one `customers` pod loses power. The pod does not close its connections; it simply stops
answering. The gateway's open sockets to it are now half-open: no FIN, no RST, nothing to read.

| Time | What happens |
|---|---|
| 0 s | The load balancer still routes a third of the calls to the dead pod's address. Each of those reads blocks. |
| ~6 s | About 33 calls per second block and none return: all 200 gateway threads are stuck in `SocketInputStream.read`. |
| 6 s onward | The gateway stops answering everything, including routes that never touch `customers`, and its health check. |
| minutes | Service discovery removes the dead pod. New calls go to healthy pods, but the 200 stuck threads do not come back. |
| hours | With no read timeout, a blocked read on a half-open connection ends only when TCP gives up. On Linux, keepalive is off unless the socket enables it, and its default idle time is 2 hours. In practice someone restarts the gateway. |

A circuit breaker does not save this. Resilience4j records a call when it completes; a call that never completes is
neither a failure nor a slow call until it returns, which here is never
([ST-R4J-002](../rules/ST-R4J-002.md) covers breakers that are blind to slow calls). Retries do not help either: they
add more calls to the same dead socket pool.

With a 1 s connect timeout and a 3 s read timeout, the same night costs a third of the `customers` calls failing
fast for a few minutes. The gateway's threads keep turning over, the other routes keep working, and nothing needs a
restart.

## How often it shows up

In our [corpus of 20 public Spring Boot repositories](../corpus/2026-11-corpus-report.md) (re-run with the released
0.4.0 CLI, [identical results](../corpus/2026-11-09-0.4.0-rerun.md)), ST-HTTP-001 reported **6 clients in 5
repositories**, with **0 false positives** in the author's review:

| Repository | Client | Note |
|---|---|---|
| spring-petclinic/spring-petclinic-microservices | `@LoadBalanced new RestTemplate()` in the API gateway ([`ApiGatewayApplication.java#L52-L56`](https://github.com/spring-petclinic/spring-petclinic-microservices/blob/e8b747ab522721076cacb5cf688eb8a54ba8c5c9/spring-petclinic-api-gateway/src/main/java/org/springframework/samples/petclinic/api/ApiGatewayApplication.java#L52-L56)) | inter-service calls |
| spring-petclinic/spring-petclinic-microservices | `RestClient.builder().build()` in the GenAI service ([`AIDataProvider.java#L37`](https://github.com/spring-petclinic/spring-petclinic-microservices/blob/e8b747ab522721076cacb5cf688eb8a54ba8c5c9/spring-petclinic-genai-service/src/main/java/org/springframework/samples/petclinic/genai/AIDataProvider.java#L37)) | inter-service calls |
| microservices-patterns/ftgo-application | `WebClient.create(...)` in the API gateway | no response timeout |
| macrozheng/mall | `new RestTemplate()` in the `mall-demo` module | demo module |
| xkcoding/spring-boot-demo | `new RestTemplate()` in an OAuth resource-server demo | tutorial |
| halo-dev/halo | `WebClient.create(...)` in a UI proxy filter | low value: enabled only in `application-dev.yaml` |

By API: 3 `new RestTemplate()`, 2 `WebClient.create(...)`, 1 `RestClient.builder().build()`. The count is small
because the rule is deliberately narrow (see below): it reports only clients whose missing timeout is certain.

## The fix

Every client needs a **connect timeout** (how long to wait for the TCP handshake) and a **read or response
timeout** (how long to wait for data). Set both below the timeout of whoever calls you.

**RestTemplate** through Spring Boot's builder (Boot 3.4+ method names; older versions use `setConnectTimeout` /
`setReadTimeout`):

```java
@Bean @LoadBalanced
RestTemplate loadBalancedRestTemplate(RestTemplateBuilder builder) {
    return builder.connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(3)).build();
}
```

**RestClient** with an explicit request factory:

```java
var factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
factory.setReadTimeout(Duration.ofSeconds(3));
RestClient client = RestClient.builder().requestFactory(factory).build();
```

**WebClient** on Reactor Netty:

```java
HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
        .responseTimeout(Duration.ofSeconds(3));
WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
```

Alternatively, inject the builder Spring Boot configures and set the timeouts as properties
(`spring.http.client.*` or `spring.http.clients.*`, depending on your Spring Boot version; check its reference
documentation). Then one place sets them for every client built from that builder.

Then decide what a timeout means for the caller: return a 503 or a fallback, and retry only with backoff and jitter
([ST-R4J-001](../rules/ST-R4J-001.md)).

## How the rule catches it

[ST-HTTP-001](../rules/ST-HTTP-001.md) parses the Java sources of every module and reports:

- `new RestTemplate()`;
- `RestClient.create()`, `RestClient.create("url")`, `RestClient.create(URI.create(...))`, and
  `RestClient.builder()…build()` without `requestFactory(...)` or `apply(...)`;
- `WebClient.create(...)`, and `WebClient.builder()…build()` without `clientConnector(...)`, `apply(...)` or
  `exchangeFunction(...)`;
- `new RestTemplateBuilder()…build()` without timeout or request-factory calls.

It deliberately does **not** report an injected, Boot-managed builder (its timeouts may come from properties the
check cannot always see) or a builder stored in a variable and configured elsewhere. That avoids false positives at
the cost of missed cases: a clean ST-HTTP-001 does not prove every client has timeouts.

Feign clients have their own rule, [ST-HTTP-002](../rules/ST-HTTP-002.md) (default 60 s read timeout). It reported
nothing in the corpus once Ribbon-era and non-OpenFeign clients were excluded.

To accept a client, suppress it with a reason:

```yaml
# springtune.yml
suppress:
  - rule: ST-HTTP-001
    module: admin-tools
    reason: "Developer-only proxy, not deployed"
```

## See it fail

The [broken-config demo](../../examples/broken-config-ci/) includes a `new RestTemplate()` without timeouts. Its
workflow runs the SpringTune Action and turns the finding into a red check annotated on the exact line. For your own
project, add the Maven or Gradle plugin at 0.4.0 ([launch post](../launch/launch-post.md#try-it-on-your-repository-040))
and set `failOn` to `WARNING`.
