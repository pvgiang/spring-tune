package io.github.winky.springtune.limiter;

import io.github.winky.springtune.limiter.AdaptiveLimiter.Outcome;
import io.github.winky.springtune.limiter.AdaptiveLimiter.Permit;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that holds an {@link AdaptiveLimiter} permit for the duration of each limited request.
 *
 * <p>The permit is released when the filter chain returns or, if the request started async processing
 * ({@code DeferredResult}, {@code Callable}, ...), when the async cycle ends: complete (by status), error (ignored)
 * or timeout (dropped). Ordered after Boot's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}),
 * so rejections appear in {@code http.server.requests}, and before Spring Security.
 */
public class ConcurrencyLimitFilter extends OncePerRequestFilter implements Ordered {

    /** {@code Ordered.HIGHEST_PRECEDENCE + 10}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;


    private final AdaptiveLimiter limiter;

    private final RequestPolicy policy;

    private final int rejectStatus;

    private final String retryAfter;

    ConcurrencyLimitFilter(AdaptiveLimiter limiter, RequestPolicy policy, LimiterProperties properties) {
        this.limiter = limiter;
        this.policy = policy;
        this.rejectStatus = properties.getRejectStatus();
        long seconds = properties.retryAfterSeconds();
        this.retryAfter = seconds > 0 ? Long.toString(seconds) : null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (policy.isBypassed(path, request.getLocalPort(), request.getHeader("Accept"),
                request.getHeader("Upgrade"))) {
            limiter.bypass();
            chain.doFilter(request, response);
            return;
        }
        Permit permit = limiter.acquire();
        if (permit.isRejected()) {
            reject(response);
            return;
        }
        boolean async = false;
        try {
            chain.doFilter(request, response);
            async = request.isAsyncStarted() && holdUntilAsyncEnds(request, permit);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            permit.release(Outcome.IGNORE);
            throw ex;
        } finally {
            if (!async) {
                permit.release(Outcome.forStatus(response.getStatus()));
            }
        }
    }

    /** Moves the release to the end of the async cycle; false if the cycle already ended (release now). */
    private static boolean holdUntilAsyncEnds(HttpServletRequest request, Permit permit) {
        try {
            request.getAsyncContext().addListener(new PermitReleasingListener(permit));
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(rejectStatus);
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        response.setContentType("text/plain;charset=UTF-8");
        response.setContentLength(RequestPolicy.REJECTION_BODY.length);
        response.getOutputStream().write(RequestPolicy.REJECTION_BODY);
    }

    /** Releases the permit at the end of the async cycle; the first event wins. */
    private static final class PermitReleasingListener implements AsyncListener {

        private final Permit permit;

        PermitReleasingListener(Permit permit) {
            this.permit = permit;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            ServletResponse response = event.getSuppliedResponse();
            int status = response instanceof HttpServletResponse http ? http.getStatus() : 200;
            permit.release(Outcome.forStatus(status));
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            permit.release(Outcome.DROPPED);
        }

        @Override
        public void onError(AsyncEvent event) {
            permit.release(Outcome.IGNORE);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A new async cycle on the same request (re-dispatch, then startAsync again): keep listening.
            event.getAsyncContext().addListener(this);
        }
    }
}
