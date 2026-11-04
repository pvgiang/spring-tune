package io.github.winky.springtune.boot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One audited apply-mode action: an applied change (updated in place while it is verified), a refusal, a
 * manual revert or a freeze. Logged on the {@code springtune.audit} logger, published as a
 * {@link SpringTuneChangeEvent} and listed by {@code GET /actuator/tuning}.
 *
 * @param id         sequence id within this process, e.g. {@code change-3}
 * @param at         when the request was made
 * @param action     {@code apply}, {@code revert} or {@code freeze}
 * @param who        principal name of the caller (or {@code springtune} for automatic actions)
 * @param why        the caller's reason
 * @param ruleId     recommendation rule the change implements, or {@code null}
 * @param knobId     knob concerned, or {@code null}
 * @param before     live values before the change (primary setting first, then settings moved with it)
 * @param after      values written
 * @param outcome    current outcome; {@link Outcome#VERIFYING} while the change is in flight
 * @param verdict    verification verdict ({@code OK}, {@code REGRESSED}, {@code INCONCLUSIVE}) or {@code null}
 * @param code       refusal code for {@link Outcome#REFUSED}, otherwise {@code null}
 * @param reasons    plain-language reasons for the outcome
 * @param evidence   baseline (B) vs verification (V) values of the knob's own signals; HTTP values are
 *                   evidence only and never trigger a revert
 * @param finishedAt when the outcome became final, or {@code null}
 */
public record ChangeAuditEntry(
        String id,
        Instant at,
        String action,
        String who,
        String why,
        String ruleId,
        String knobId,
        Map<String, Long> before,
        Map<String, Long> after,
        Outcome outcome,
        String verdict,
        String code,
        List<String> reasons,
        Map<String, String> evidence,
        Instant finishedAt) {

    public ChangeAuditEntry {
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
        reasons = List.copyOf(reasons);
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    ChangeAuditEntry finish(Outcome outcome, String verdict, List<String> reasons, Map<String, String> evidence,
            Instant finishedAt) {
        return new ChangeAuditEntry(id, at, action, who, why, ruleId, knobId, before, after, outcome, verdict, code,
                reasons, evidence, finishedAt);
    }

    public enum Outcome {
        /** Applied, verification running. */
        VERIFYING,
        /** Verified: the knob's own signals did not regress. */
        CONFIRMED,
        /** Could not be verified and {@code on-inconclusive=keep}: left in place. */
        KEPT_INCONCLUSIVE,
        /** Written back to the before value (regression, inconclusive, manual revert or freeze). */
        REVERTED,
        /** The live value was changed by someone else during verification; SpringTune left it alone. */
        SUPERSEDED,
        /** Writing the before value back failed; apply mode is frozen until restart. */
        REVERT_FAILED,
        /** The write itself failed; the before value was restored. */
        APPLY_FAILED,
        /** The guard refused the request; nothing was changed. */
        REFUSED,
        /** Apply mode was frozen until restart. */
        FROZEN;

        public boolean isFinal() {
            return this != VERIFYING;
        }
    }
}
