package io.github.winkytek.springtune.boot;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code /actuator/tuning}: knobs, live signals, budget, explained recommendations and their apply plans.
 * Read-only: the only write operation is the web-only {@link TuningApplyWebExtension}, which exists only in
 * {@code springtune.mode=apply}. Expose it with {@code management.endpoints.web.exposure.include=tuning}.
 */
@Endpoint(id = "tuning")
public class TuningEndpoint {

    private final TuningService service;

    public TuningEndpoint(TuningService service) {
        this.service = service;
    }

    @ReadOperation
    public TuningSnapshot tuning() {
        return service.snapshot();
    }
}
