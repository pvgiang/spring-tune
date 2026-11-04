package io.github.winky.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The caller as the write operation sees it. Spring Security's anonymous authentication ({@code anonymousUser})
 * is a non-null principal on WebFlux (and with custom security contexts); it must count as unauthenticated.
 */
class TuningApplyWebExtensionTest {

    private final List<ChangeAuditEntry> events = new ArrayList<>();

    private TuningApplyWebExtension extension() {
        SpringTuneProperties properties = new SpringTuneProperties();
        properties.setMode(SpringTuneProperties.Mode.APPLY);
        ChangeController controller = new ChangeController(properties, new MutableClock(Instant.EPOCH), List::of,
                now -> List.of(), Optional.empty(), events::add);
        return new TuningApplyWebExtension(controller, null);
    }

    private static SecurityContext context(Principal principal) {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }
        };
    }

    @Test
    void anonymousAuthenticationIsNotAnAuthenticatedCaller() {
        Principal anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        WebEndpointResponse<ChangeResponse> response = extension().change("freeze", null, null, null, "x",
                context(anonymous));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getBody().code()).isEqualTo("NOT_AUTHENTICATED");
        assertThat(response.getBody().change().who()).isEqualTo("anonymous");
    }

    @Test
    void namedPrincipalIsTheCaller() {
        WebEndpointResponse<ChangeResponse> response = extension().change("freeze", null, null, null, "x",
                context(() -> "ops"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody().change().who()).isEqualTo("ops");
    }
}
