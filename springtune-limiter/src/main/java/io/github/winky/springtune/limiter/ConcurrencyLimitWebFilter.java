package io.github.winky.springtune.limiter;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Outcome;
import io.github.winky.springtune.limiter.AdaptiveLimiter.Permit;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * WebFlux filter that holds an {@link AdaptiveLimiter} permit from subscription until the exchange completes,
 * errors (ignored) or is cancelled, e.g. by a client disconnect (ignored). WebFlux observation runs in
 * {@code HttpWebHandlerAdapter}, outside every {@code WebFilter}, so rejections appear in
 * {@code http.server.requests}.
 */
public class ConcurrencyLimitWebFilter implements WebFilter, Ordered {

    /** {@code Ordered.HIGHEST_PRECEDENCE + 10}, before Spring Security's {@code WebFilterChainProxy}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private final AdaptiveLimiter limiter;

    private final RequestPolicy policy;

    private final HttpStatusCode rejectStatus;

    private final String retryAfter;

    ConcurrencyLimitWebFilter(AdaptiveLimiter limiter, RequestPolicy policy, LimiterProperties properties) {
        this.limiter = limiter;
        this.policy = policy;
        this.rejectStatus = HttpStatusCode.valueOf(properties.getRejectStatus());
        long seconds = properties.retryAfterSeconds();
        this.retryAfter = seconds > 0 ? Long.toString(seconds) : null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            ServerHttpRequest request = exchange.getRequest();
            InetSocketAddress local = request.getLocalAddress();
            HttpHeaders headers = request.getHeaders();
            if (policy.isBypassed(request.getPath().pathWithinApplication().value(),
                    local != null ? local.getPort() : -1, headers.getFirst(HttpHeaders.ACCEPT),
                    headers.getFirst(HttpHeaders.UPGRADE))) {
                limiter.bypass();
                return chain.filter(exchange);
            }
            Permit permit = limiter.acquire();
            if (permit.isRejected()) {
                return reject(exchange.getResponse());
            }
            return Mono.defer(() -> chain.filter(exchange))
                    .doFinally(signal -> permit.release(outcome(signal, exchange.getResponse())));
        });
    }

    private static Outcome outcome(SignalType signal, ServerHttpResponse response) {
        if (signal != SignalType.ON_COMPLETE) {
            return Outcome.IGNORE; // ON_ERROR or CANCEL (client went away)
        }
        HttpStatusCode status = response.getStatusCode();
        return Outcome.forStatus(status != null ? status.value() : 200);
    }

    private Mono<Void> reject(ServerHttpResponse response) {
        response.setStatusCode(rejectStatus);
        if (retryAfter != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        response.getHeaders().setContentLength(RequestPolicy.REJECTION_BODY.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(RequestPolicy.REJECTION_BODY)));
    }
}
