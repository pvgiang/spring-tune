package io.github.pvgiang.springtune.boot;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code /actuator/tuning}: knobs, live signals, budget and explained recommendations. Read-only: there is
 * no write operation in this release. Expose it with {@code management.endpoints.web.exposure.include=tuning}.
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
