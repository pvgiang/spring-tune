package io.github.winky.springtune.boot;

import io.github.winky.springtune.boot.ApplyGuard.Caller;
import io.github.winky.springtune.boot.ApplyGuard.ChangeRequest;
import java.security.Principal;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.EndpointWebExtension;

/**
 * {@code POST /actuator/tuning} in {@code springtune.mode=apply} (experimental). Web only: being an
 * {@link EndpointWebExtension}, JMX never gets a write operation. The bean exists only in apply mode, and
 * apply mode refuses to start without Spring Security on the classpath. Unauthenticated (also anonymous) calls
 * and calls without {@code springtune.apply.required-role} are refused (and audited); there is no anonymous switch.
 *
 * <p>Body: {@code {"action": "apply" | "revert" | "freeze", "ruleId", "knobId", "expectedCurrent", "reason"}}.
 * {@code apply} applies the {@code recommendedValue} of the matching current recommendation, compare-and-set
 * on {@code expectedCurrent}; {@code revert} writes back the change in flight (or the last confirmed one);
 * {@code freeze} reverts the change in flight and refuses further applies until restart.
 */
@EndpointWebExtension(endpoint = TuningEndpoint.class)
public class TuningApplyWebExtension {

    private final ChangeController controller;
    private final String requiredRole;

    TuningApplyWebExtension(ChangeController controller, String requiredRole) {
        this.controller = controller;
        this.requiredRole = requiredRole;
    }

    // org.springframework.lang.Nullable marks the body fields optional on Boot 3 (which only recognizes that
    // annotation) and on Boot 4 (which recognizes any annotation named Nullable); every field is validated here.
    @SuppressWarnings("deprecation")
    @WriteOperation
    public WebEndpointResponse<ChangeResponse> change(@org.springframework.lang.Nullable String action,
            @org.springframework.lang.Nullable String ruleId, @org.springframework.lang.Nullable String knobId,
            @org.springframework.lang.Nullable Long expectedCurrent, @org.springframework.lang.Nullable String reason,
            SecurityContext securityContext) {
        Principal principal = securityContext.getPrincipal();
        if (isAnonymous(principal)) {
            principal = null;
        }
        boolean hasRole = requiredRole == null || requiredRole.isBlank() || securityContext.isUserInRole(requiredRole);
        ChangeResponse response = controller.handle(new ChangeRequest(action, ruleId, knobId, expectedCurrent, reason),
                new Caller(principal == null ? null : principal.getName(), hasRole));
        return new WebEndpointResponse<>(response, response.status());
    }

    /**
     * Spring Security's anonymous authentication ({@code anonymousUser}) is a non-null principal on WebFlux
     * (when {@code anonymous()} is enabled) and with custom security contexts; it is not an authenticated caller.
     * Matched by class name, so this class does not need spring-security-core to load.
     */
    static boolean isAnonymous(Principal principal) {
        Class<?> type = principal == null ? null : principal.getClass();
        for (; type != null; type = type.getSuperclass()) {
            if (type.getName().equals("org.springframework.security.authentication.AnonymousAuthenticationToken")) {
                return true;
            }
        }
        return false;
    }
}
