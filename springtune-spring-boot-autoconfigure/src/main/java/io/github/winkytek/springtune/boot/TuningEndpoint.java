package io.github.winkytek.springtune.boot;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

/**
 * {@code /actuator/tuning}: knobs, live signals, budget, explained recommendations, per-rule evaluation statuses and
 * apply plans. {@code /actuator/tuning/report}: the allowlisted, pseudonymized {@link TuningReport} to paste into
 * the feedback form. Read-only: the only write operation is the web-only {@link TuningApplyWebExtension}, which
 * exists only in {@code springtune.mode=apply}. Expose it with
 * {@code management.endpoints.web.exposure.include=tuning}.
 */
@Endpoint(id = "tuning")
public class TuningEndpoint {

    static final String REPORT = "report";

    private final TuningService service;

    public TuningEndpoint(TuningService service) {
        this.service = service;
    }

    @ReadOperation
    public TuningSnapshot tuning() {
        return service.snapshot();
    }

    /** {@code GET /actuator/tuning/report}; any other selector returns {@code null}, which the web layer maps to 404. */
    @ReadOperation
    public TuningReport report(@Selector String part) {
        return REPORT.equals(part) ? TuningReport.from(service.snapshot()) : null;
    }
}
