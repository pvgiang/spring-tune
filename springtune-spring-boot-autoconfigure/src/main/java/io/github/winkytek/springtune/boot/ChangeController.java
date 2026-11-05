package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.boot.ApplyGuard.Caller;
import io.github.winkytek.springtune.boot.ApplyGuard.ChangeRequest;
import io.github.winkytek.springtune.boot.ApplyGuard.Code;
import io.github.winkytek.springtune.boot.ApplyGuard.Decision;
import io.github.winkytek.springtune.boot.ApplyGuard.KnobFacts;
import io.github.winkytek.springtune.boot.ApplyGuard.Refusal;
import io.github.winkytek.springtune.boot.ChangeAuditEntry.Outcome;
import io.github.winkytek.springtune.boot.Verification.Counters;
import io.github.winkytek.springtune.boot.Verification.Result;
import io.github.winkytek.springtune.boot.Verification.Verdict;
import io.github.winkytek.springtune.boot.Verification.Window;
import io.github.winkytek.springtune.model.Recommendation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Apply mode's state machine. Executes approved changes through the knob's {@link KnobWriter}, verifies
 * them on every sampler tick and reverts them when needed. At most one change is in flight per process.
 *
 * <pre>
 *   IDLE --apply--&gt; VERIFYING --OK--------------------------------&gt; CONFIRMED --cooldown--&gt; IDLE
 *                              --REGRESSED / INCONCLUSIVE(revert)--&gt; REVERTED  --cooldown-after-revert--&gt; IDLE
 *                              --INCONCLUSIVE(keep)----------------&gt; CONFIRMED (outcome KEPT_INCONCLUSIVE)
 *                              --live value changed by others-------&gt; SUPERSEDED --cooldown--&gt; IDLE
 *                              --revert write failed-----------------&gt; REVERT_FAILED (apply frozen until restart)
 * </pre>
 *
 * A revert writes the before value back only if the live value still equals the applied value; otherwise
 * the change is SUPERSEDED and left alone. Cumulative counters of every writable knob's own component are
 * recorded on every tick, so the baseline (B) and verification (V) windows can be compared
 * ({@link Verification}). Every action is audited ({@code springtune.audit} logger, {@link SpringTuneChangeEvent},
 * endpoint).
 */
final class ChangeController {

    static final String SYSTEM = "springtune";

    private static final Log AUDIT = LogFactory.getLog("springtune.audit");
    private static final Log LOG = LogFactory.getLog(ChangeController.class);

    enum State {
        IDLE,
        VERIFYING,
        CONFIRMED,
        REVERTED,
        SUPERSEDED,
        REVERT_FAILED
    }

    private record KnobState(State state, Instant since, Instant cooldownUntil) {
    }

    /**
     * The change being verified. Its audit entry is kept here, not looked up in the bounded audit, which may
     * already have evicted it (e.g. after many refusals during the verification window).
     */
    private record InFlight(WritableKnob knob, ChangeAuditEntry entry, Instant t0, Window baseline,
            Map<String, Long> before, Map<String, Long> after) {

        String entryId() {
            return entry.id();
        }
    }

    /** Last change of a knob that may still be reverted on request (confirmed or kept). */
    private record Applied(Map<String, Long> before, Map<String, Long> after, String ruleId) {
    }

    private final SpringTuneProperties properties;
    private final SpringTuneProperties.Apply settings;
    private final Clock clock;
    private final Supplier<List<WritableKnob>> knobs;
    private final Function<Instant, List<Recommendation>> recommender;
    private final Optional<Integer> budgetCap;
    private final Consumer<ChangeAuditEntry> listener;
    private final Duration retention;

    private final Map<String, SampleWindow<Counters>> history = new HashMap<>();
    private final Map<String, KnobState> states = new HashMap<>();
    private final Map<String, Applied> applied = new HashMap<>();
    private final Deque<ChangeAuditEntry> audit = new ArrayDeque<>();
    private InFlight inFlight;
    private boolean frozen;
    private String frozenReason;
    private long sequence;

    ChangeController(SpringTuneProperties properties, Clock clock, Supplier<List<WritableKnob>> knobs,
            Function<Instant, List<Recommendation>> recommender, Optional<Integer> budgetCap,
            Consumer<ChangeAuditEntry> listener) {
        this.properties = properties;
        this.settings = properties.getApply();
        this.clock = clock;
        this.knobs = knobs;
        this.recommender = recommender;
        this.budgetCap = budgetCap;
        this.listener = listener;
        validate(settings);
        this.retention = settings.getBaseline().plus(settings.getWarmup()).plus(settings.getVerificationWindow())
                .plus(properties.getSampling().getInterval().multipliedBy(2));
    }

    private static void validate(SpringTuneProperties.Apply settings) {
        if (!(settings.getMaxStep() > 0 && settings.getMaxStep() <= 1)) {
            throw new IllegalArgumentException("springtune.apply.max-step must be in (0, 1]");
        }
        if (!(settings.getMaxGrowthFactor() >= 1)) {
            throw new IllegalArgumentException("springtune.apply.max-growth-factor must be >= 1");
        }
        for (Duration d : List.of(settings.getBaseline(), settings.getVerificationWindow(), settings.getCooldown(),
                settings.getCooldownAfterRevert())) {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("springtune.apply baseline, verification-window, cooldown and"
                        + " cooldown-after-revert must be positive");
            }
        }
        if (settings.getWarmup().isNegative()) {
            throw new IllegalArgumentException("springtune.apply.warmup must not be negative");
        }
        if (settings.getAuditSize() < 1) {
            throw new IllegalArgumentException("springtune.apply.audit-size must be >= 1");
        }
    }

    // ------------------------------------------------------------------ requests

    synchronized ChangeResponse handle(ChangeRequest request, Caller caller) {
        Instant now = clock.instant();
        Optional<Refusal> refusal = ApplyGuard.checkCaller(caller, settings.getRequiredRole())
                .or(() -> ApplyGuard.checkRequest(request));
        if (refusal.isPresent()) {
            return refuse(request, caller, refusal.get(), now);
        }
        return switch (request.normalizedAction()) {
            case "apply" -> apply(request, caller.name(), now);
            case "revert" -> revert(request, caller.name(), now);
            default -> freeze(request, caller.name(), now);
        };
    }

    private ChangeResponse apply(ChangeRequest request, String who, Instant now) {
        Optional<WritableKnob> knob = knob(request.knobId());
        Optional<KnobFacts> facts = knob.map(k -> new KnobFacts(k.knobId(), k.kind(), k.current(), k.configuredValue(),
                cooldownUntil(k.knobId()), baselineStart(k.knobId(), now) != null));
        ApplyGuard.State state = new ApplyGuard.State(properties.getMode(), settings.isEnabled(), frozen, inFlight != null);
        List<Recommendation> fresh = recommender.apply(now);
        Decision decision = ApplyGuard.checkApply(request, state, facts, fresh, budgetCap, settings, now);
        if (!decision.approved()) {
            return refuse(request, new Caller(who, true), decision.refusal(), now);
        }
        WritableKnob target = knob.get();
        Recommendation rec = decision.recommendation();
        Counters start = baselineStart(target.knobId(), now);
        Counters current = new Counters(now, target.counters().get());
        history(target.knobId()).add(current);
        Window baseline = Window.between(start, current);

        Map<String, Long> after = target.writer().plan(rec.recommendedValue());
        Map<String, Long> live = target.writer().read();
        Map<String, Long> before = new LinkedHashMap<>();
        after.keySet().forEach(key -> before.put(key, live.get(key)));
        List<String> reasons = new ArrayList<>();
        reasons.add("Recommendation " + rec.ruleId() + ": " + rec.reason());
        try {
            target.writer().write(after);
            long written = target.current();
            if (written != after.get(target.writer().setting())) {
                throw new IllegalStateException("live value is " + written + " after writing "
                        + after.get(target.writer().setting()));
            }
        } catch (RuntimeException ex) {
            reasons.add("Write failed: " + ex.getMessage());
            Outcome outcome = Outcome.APPLY_FAILED;
            try {
                target.writer().write(before);
                reasons.add("Before value restored.");
            } catch (RuntimeException restoreFailure) {
                outcome = Outcome.REVERT_FAILED;
                freezeInternally("restoring " + target.knobId() + " after a failed write failed: "
                        + restoreFailure.getMessage());
                reasons.add("Restoring the before value failed too: " + restoreFailure.getMessage()
                        + ". Apply mode is frozen until restart.");
                states.put(target.knobId(), new KnobState(State.REVERT_FAILED, now, null));
            }
            ChangeAuditEntry entry = record(new ChangeAuditEntry(nextId(), now, "apply", who, request.reason(),
                    rec.ruleId(), target.knobId(), before, after, outcome, null, null, reasons, Map.of(), now));
            return new ChangeResponse(409, outcome.name(), String.join(" ", reasons), entry);
        }
        reasons.add("Ephemeral: lost on restart. Verifying for " + Format.duration(settings.getVerificationWindow())
                + " after a " + Format.duration(settings.getWarmup()) + " warmup.");
        ChangeAuditEntry entry = record(new ChangeAuditEntry(nextId(), now, "apply", who, request.reason(),
                rec.ruleId(), target.knobId(), before, after, Outcome.VERIFYING, null, null, reasons, Map.of(), null));
        inFlight = new InFlight(target, entry, now, baseline, before, after);
        states.put(target.knobId(), new KnobState(State.VERIFYING, now, null));
        return new ChangeResponse(200, "OK", "Applied " + target.knobId() + " " + before.get(target.writer().setting())
                + " -> " + after.get(target.writer().setting()) + "; verifying.", entry);
    }

    private ChangeResponse revert(ChangeRequest request, String who, Instant now) {
        String why = "Manual revert by " + who + (request.reason() == null ? "" : ": " + request.reason());
        if (inFlight != null && inFlight.knob().knobId().equals(request.knobId())) {
            ChangeAuditEntry entry = finishByRevert(why, null, List.of(why), Map.of(), now);
            return new ChangeResponse(200, "OK", entry.outcome() + ": " + String.join(" ", entry.reasons()), entry);
        }
        Applied last = applied.get(request.knobId());
        Optional<WritableKnob> knob = knob(request.knobId());
        if (last == null || knob.isEmpty()) {
            return refuse(request, new Caller(who, true), new Refusal(Code.NOTHING_TO_REVERT,
                    "No change of " + request.knobId() + " is in flight or confirmed."), now);
        }
        WritableKnob target = knob.get();
        long live = target.current();
        long appliedValue = last.after().get(target.writer().setting());
        if (live != appliedValue) {
            return refuse(request, new Caller(who, true), new Refusal(Code.NOTHING_TO_REVERT, "The live value " + live
                    + " is no longer the applied value " + appliedValue + "; SpringTune leaves it alone."), now);
        }
        List<String> reasons = new ArrayList<>(List.of(why));
        Outcome outcome = restore(target, last.before(), last.after(), reasons, now);
        applied.remove(request.knobId());
        ChangeAuditEntry entry = record(new ChangeAuditEntry(nextId(), now, "revert", who, request.reason(),
                last.ruleId(), target.knobId(), last.after(), last.before(), outcome, null, null, reasons, Map.of(), now));
        return new ChangeResponse(outcome == Outcome.REVERTED ? 200 : 409, outcome == Outcome.REVERTED ? "OK"
                : outcome.name(), String.join(" ", reasons), entry);
    }

    private ChangeResponse freeze(ChangeRequest request, String who, Instant now) {
        String why = "Frozen by " + who + (request.reason() == null ? "" : ": " + request.reason());
        if (inFlight != null) {
            finishByRevert(why, null, List.of(why), Map.of(), now);
        }
        freezeInternally(why);
        ChangeAuditEntry entry = record(new ChangeAuditEntry(nextId(), now, "freeze", who, request.reason(), null,
                null, Map.of(), Map.of(), Outcome.FROZEN, null, null, List.of(why + ". Applies are refused until"
                        + " restart."), Map.of(), now));
        return new ChangeResponse(200, "OK", "Apply mode frozen until restart.", entry);
    }

    private ChangeResponse refuse(ChangeRequest request, Caller caller, Refusal refusal, Instant now) {
        ChangeAuditEntry entry = record(new ChangeAuditEntry(nextId(), now, request.normalizedAction(),
                caller.name() == null ? "anonymous" : caller.name(), request.reason(), request.ruleId(),
                request.knobId(), Map.of(), Map.of(), Outcome.REFUSED, null, refusal.code().name(),
                List.of(refusal.message()), Map.of(), now), caller.name() != null);
        return new ChangeResponse(refusal.status(), refusal.code().name(), refusal.message(), entry);
    }

    // ------------------------------------------------------------------ ticks

    /** Called after every sampler tick: records counters and drives the change in flight. */
    synchronized void tick(Instant now) {
        for (WritableKnob knob : knobs.get()) {
            try {
                Map<String, Double> counters = knob.counters().get();
                if (!counters.isEmpty()) {
                    history(knob.knobId()).add(new Counters(now, counters));
                }
            } catch (RuntimeException ex) {
                LOG.debug("SpringTune could not read counters of " + knob.knobId(), ex);
            }
        }
        InFlight change = inFlight;
        if (change != null) {
            try {
                progress(now);
            } catch (RuntimeException ex) {
                LOG.warn("SpringTune could not verify change " + change.entryId(), ex);
            }
        }
    }

    private void progress(Instant now) {
        InFlight change = inFlight;
        String setting = change.knob().writer().setting();
        long live = change.knob().current();
        long appliedValue = change.after().get(setting);
        if (live != appliedValue) {
            supersede(change, live, now);
            return;
        }
        Instant vFrom = change.t0().plus(settings.getWarmup());
        if (now.isBefore(vFrom)) {
            return;
        }
        boolean windowOver = !now.isBefore(vFrom.plus(settings.getVerificationWindow()));
        SampleWindow<Counters> counters = history(change.knob().knobId());
        Counters vStart = counters.firstAtOrAfter(vFrom);
        Counters vEnd = counters.latest();
        Result result;
        if (vStart == null || vEnd == null || !vEnd.at().isAfter(vStart.at())) {
            if (!windowOver) {
                return;
            }
            result = new Result(Verdict.INCONCLUSIVE, List.of("No counters were recorded during the verification"
                    + " window."), Map.of());
        } else {
            Window v = Window.between(vStart, vEnd);
            result = Verification.verify(change.knob().kind(), change.baseline(), v);
            if (!windowOver) {
                if (Verification.fastFail(change.knob().kind(), change.baseline(), v)) {
                    finishByRevert("Fast fail (R1) during verification.", Verdict.REGRESSED, result.reasons(),
                            result.evidence(), now);
                }
                return;
            }
        }
        switch (result.verdict()) {
            case OK -> confirm(change, Outcome.CONFIRMED, result, now);
            case REGRESSED -> finishByRevert("Regression: the knob's own signals got worse.", Verdict.REGRESSED,
                    result.reasons(), result.evidence(), now);
            default -> {
                if (settings.getOnInconclusive() == SpringTuneProperties.Apply.OnInconclusive.KEEP) {
                    confirm(change, Outcome.KEPT_INCONCLUSIVE, result, now);
                } else {
                    finishByRevert("Inconclusive, and springtune.apply.on-inconclusive=revert: every change must be"
                            + " verified.", Verdict.INCONCLUSIVE, result.reasons(), result.evidence(), now);
                }
            }
        }
    }

    private void confirm(InFlight change, Outcome outcome, Result result, Instant now) {
        List<String> reasons = new ArrayList<>(result.reasons());
        if (outcome == Outcome.KEPT_INCONCLUSIVE) {
            reasons.add(0, "Kept although not verified (springtune.apply.on-inconclusive=keep).");
        }
        finish(change, outcome, result.verdict().name(), reasons, result.evidence(), now);
        states.put(change.knob().knobId(), new KnobState(State.CONFIRMED, now, now.plus(settings.getCooldown())));
        applied.put(change.knob().knobId(), new Applied(change.before(), change.after(), ruleIdOf(change)));
    }

    private ChangeAuditEntry supersede(InFlight change, long live, Instant now) {
        ChangeAuditEntry entry = finish(change, Outcome.SUPERSEDED, null, List.of("The live value changed to " + live
                + " (applied " + change.after().get(change.knob().writer().setting()) + ") by someone else; SpringTune"
                + " leaves it alone."), Map.of(), now);
        states.put(change.knob().knobId(), new KnobState(State.SUPERSEDED, now, now.plus(settings.getCooldown())));
        return entry;
    }

    /** Reverts the change in flight (automatic, manual or freeze) and returns its final audit entry. */
    private ChangeAuditEntry finishByRevert(String cause, Verdict verdict, List<String> details,
            Map<String, String> evidence, Instant now) {
        InFlight change = inFlight;
        String setting = change.knob().writer().setting();
        long live = change.knob().current();
        if (live != change.after().get(setting)) {
            return supersede(change, live, now);
        }
        List<String> reasons = new ArrayList<>();
        reasons.add(cause);
        reasons.addAll(details.stream().filter(d -> !d.equals(cause)).toList());
        Outcome outcome = restore(change.knob(), change.before(), change.after(), reasons, now);
        return finish(change, outcome, verdict == null ? null : verdict.name(), reasons, evidence, now);
    }

    /**
     * Writes {@code before} back, restricted to settings whose live value still equals the applied value. On
     * failure apply mode is frozen until restart.
     */
    private Outcome restore(WritableKnob knob, Map<String, Long> before, Map<String, Long> after, List<String> reasons,
            Instant now) {
        Map<String, Long> live = knob.writer().read();
        Map<String, Long> restore = new LinkedHashMap<>();
        before.forEach((key, value) -> {
            if (key.equals(knob.writer().setting()) || after.get(key).equals(live.get(key))) {
                restore.put(key, value);
            }
        });
        try {
            knob.writer().write(restore);
            long written = knob.current();
            if (written != before.get(knob.writer().setting())) {
                throw new IllegalStateException("live value is " + written + " after writing back "
                        + before.get(knob.writer().setting()));
            }
            reasons.add("Reverted to " + restore + ".");
            states.put(knob.knobId(), new KnobState(State.REVERTED, now, now.plus(settings.getCooldownAfterRevert())));
            return Outcome.REVERTED;
        } catch (RuntimeException ex) {
            String message = "Revert of " + knob.knobId() + " failed: " + ex.getMessage();
            reasons.add(message + ". Apply mode is frozen until restart; fix the value manually.");
            states.put(knob.knobId(), new KnobState(State.REVERT_FAILED, now, null));
            freezeInternally(message);
            LOG.warn("SpringTune " + message + ". Apply mode is frozen until restart.", ex);
            return Outcome.REVERT_FAILED;
        }
    }

    private ChangeAuditEntry finish(InFlight change, Outcome outcome, String verdict, List<String> reasons,
            Map<String, String> evidence, Instant now) {
        inFlight = null;
        return record(change.entry().finish(outcome, verdict, reasons, evidence, now));
    }

    private String ruleIdOf(InFlight change) {
        return change.entry().ruleId();
    }

    private void freezeInternally(String reason) {
        if (!frozen) {
            frozen = true;
            frozenReason = reason;
        }
    }

    // ------------------------------------------------------------------ audit

    private String nextId() {
        return "change-" + (++sequence);
    }

    /** Adds or replaces (same id) an audit entry, logs it and publishes it. An evicted entry is added again. */
    private ChangeAuditEntry record(ChangeAuditEntry entry) {
        return record(entry, true);
    }

    /**
     * @param keep whether the entry goes into the bounded in-memory audit. Unauthenticated refusals are only
     *             logged and counted, so anonymous requests cannot push genuine entries out of the audit.
     */
    private ChangeAuditEntry record(ChangeAuditEntry entry, boolean keep) {
        if (!keep) {
            AUDIT.info(auditLine(entry));
            try {
                listener.accept(entry);
            } catch (RuntimeException ex) {
                LOG.warn("SpringTune change listener failed", ex);
            }
            return entry;
        }
        boolean replaced = false;
        List<ChangeAuditEntry> entries = new ArrayList<>(audit);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(entry.id())) {
                entries.set(i, entry);
                replaced = true;
            }
        }
        if (!replaced) {
            entries.add(0, entry);
        }
        audit.clear();
        audit.addAll(entries.subList(0, Math.min(entries.size(), settings.getAuditSize())));
        String line = auditLine(entry);
        if (entry.outcome() == Outcome.REVERT_FAILED) {
            AUDIT.warn(line);
        } else {
            AUDIT.info(line);
        }
        try {
            listener.accept(entry);
        } catch (RuntimeException ex) {
            LOG.warn("SpringTune change listener failed for " + entry.id(), ex);
        }
        return entry;
    }

    /** The {@code springtune.audit} log line of an entry. */
    static String auditLine(ChangeAuditEntry entry) {
        String line = entry.id() + " " + entry.action() + " by " + entry.who() + " outcome=" + entry.outcome()
                + (entry.code() != null ? " code=" + entry.code() : "")
                + (entry.knobId() != null ? " knob=" + entry.knobId() : "")
                + (entry.ruleId() != null ? " rule=" + entry.ruleId() : "")
                + (entry.before().isEmpty() ? "" : " before=" + entry.before() + " after=" + entry.after())
                + (entry.verdict() != null ? " verdict=" + entry.verdict() : "")
                + " why=\"" + (entry.why() == null ? "" : entry.why()) + "\" reasons=" + entry.reasons()
                + (entry.evidence().isEmpty() ? "" : " evidence=" + entry.evidence());
        // Caller-supplied text (why, ruleId, knobId, principal) must not start a forged log line.
        return line.replace("\r", "\\r").replace("\n", "\\n");
    }

    // ------------------------------------------------------------------ state

    private Optional<WritableKnob> knob(String knobId) {
        return knobs.get().stream().filter(k -> k.knobId().equals(knobId)).findFirst();
    }

    private SampleWindow<Counters> history(String knobId) {
        return history.computeIfAbsent(knobId, id -> new SampleWindow<>(retention));
    }

    /** Counters at the start of the baseline window, or {@code null} when history does not reach back that far. */
    private Counters baselineStart(String knobId, Instant now) {
        return history(knobId).atOrBefore(now.minus(settings.getBaseline()).plus(properties.getSampling().getInterval()));
    }

    private Instant cooldownUntil(String knobId) {
        KnobState state = states.get(knobId);
        return state == null ? null : state.cooldownUntil();
    }

    private KnobState effectiveState(String knobId, Instant now) {
        KnobState state = states.get(knobId);
        if (state == null) {
            return new KnobState(State.IDLE, null, null);
        }
        if (state.cooldownUntil() != null && !now.isBefore(state.cooldownUntil())) {
            return new KnobState(State.IDLE, state.cooldownUntil(), null);
        }
        return state;
    }

    synchronized State state(String knobId) {
        return effectiveState(knobId, clock.instant()).state();
    }

    synchronized boolean frozen() {
        return frozen;
    }

    synchronized List<ChangeAuditEntry> audit() {
        return List.copyOf(audit);
    }

    synchronized TuningSnapshot.Apply view() {
        Instant now = clock.instant();
        List<TuningSnapshot.WritableKnobView> views = new ArrayList<>();
        for (WritableKnob knob : knobs.get()) {
            try {
                long current = knob.current();
                ApplyGuard.Limits limits = ApplyGuard.limits(current, knob.configuredValue(), knob.kind(), budgetCap,
                        settings);
                KnobState state = effectiveState(knob.knobId(), now);
                views.add(new TuningSnapshot.WritableKnobView(knob.knobId(), knob.componentId(), knob.writer().setting(),
                        knob.configuredValue(), current, limits.min(), limits.max(), limits.maxStep(),
                        state.state().name(), state.since(), state.cooldownUntil()));
            } catch (RuntimeException ex) {
                LOG.debug("SpringTune could not read " + knob.knobId(), ex);
            }
        }
        Map<String, String> guardrails = new LinkedHashMap<>();
        guardrails.put("maxStep", String.valueOf(settings.getMaxStep()));
        guardrails.put("maxGrowthFactor", String.valueOf(settings.getMaxGrowthFactor()));
        guardrails.put("baseline", Format.duration(settings.getBaseline()));
        guardrails.put("warmup", Format.duration(settings.getWarmup()));
        guardrails.put("verificationWindow", Format.duration(settings.getVerificationWindow()));
        guardrails.put("onInconclusive", settings.getOnInconclusive().name());
        guardrails.put("cooldown", Format.duration(settings.getCooldown()));
        guardrails.put("cooldownAfterRevert", Format.duration(settings.getCooldownAfterRevert()));
        guardrails.put("changesInFlight", inFlight == null ? "0" : "1 (" + inFlight.entryId() + ")");
        guardrails.put("persistence", "ephemeral: applied values are lost on restart");
        return new TuningSnapshot.Apply(settings.isEnabled(), frozen, frozenReason, settings.getRequiredRole(),
                guardrails, views, List.copyOf(audit));
    }
}
