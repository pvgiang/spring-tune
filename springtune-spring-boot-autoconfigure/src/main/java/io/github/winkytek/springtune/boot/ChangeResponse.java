package io.github.winkytek.springtune.boot;

/**
 * Body of {@code POST /actuator/tuning} (apply mode).
 *
 * @param status  HTTP status, also used as the response status: 200 done, 400 invalid or outside the
 *                guardrails, 401 unauthenticated, 403 missing role, 409 conflicts with the current state
 *                (stale {@code expectedCurrent}, no current recommendation, cooldown, change in flight, frozen)
 * @param code    {@code OK} or the refusal code, e.g. {@code STALE_EXPECTED_CURRENT}
 * @param message plain-language explanation
 * @param change  the audit entry created or updated by the request, or {@code null}
 */
public record ChangeResponse(int status, String code, String message, ChangeAuditEntry change) {
}
