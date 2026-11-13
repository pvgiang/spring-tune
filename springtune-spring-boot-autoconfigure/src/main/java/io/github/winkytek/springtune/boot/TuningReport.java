package io.github.winkytek.springtune.boot;

import io.github.winkytek.springtune.model.Knob;
import io.github.winkytek.springtune.model.Recommendation;
import io.github.winkytek.springtune.model.Recommendation.Action;
import io.github.winkytek.springtune.model.Recommendation.Confidence;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.SpringBootVersion;

/**
 * Payload of {@code GET /actuator/tuning/report}: a shareable summary of {@code GET /actuator/tuning}, meant to be
 * pasted into the recommendation feedback form.
 *
 * <p><b>Built from an allowlist, never scrubbed.</b> Only these values are copied from the snapshot: enum names,
 * numbers, instants, the rule ids SpringTune defines ({@link #RULES}), setting names from {@link #SETTINGS}, evidence
 * entries whose key is in {@link #EVIDENCE_KEYS} and whose value is a number with an optional unit, meter names from
 * a fixed set and refusal codes that are upper-case constants. Component ids (pool and bean names), knob ids,
 * signals, reasons, principals ({@code who}), the caller's {@code why}, exception messages and every other free
 * text are never read into it. Anything that does not match is dropped, not rewritten.
 *
 * <p><b>Pseudonyms.</b> Components are referred to as {@code <kind>#<n>} ({@code hikari#1}, {@code executor#2}),
 * numbered per kind in the order of their component id (sorted), within this process; a knob is
 * {@code <ref>.<setting>} ({@code hikari#1.maximumPoolSize}). The mapping is not part of the report: the same pool
 * keeps its number as long as the set of components does not change, which is normally the life of the process.
 *
 * @param format            always {@value #FORMAT}
 * @param springtuneVersion version of the starter, or {@code unknown}
 * @param springBootVersion {@link SpringBootVersion#getVersion()}, or {@code unknown}
 * @param javaVersion       {@code Runtime.version().feature()}
 * @param omitted           categories of the snapshot that are never copied (fixed list)
 */
public record TuningReport(
        String format,
        String springtuneVersion,
        String springBootVersion,
        int javaVersion,
        String mode,
        Instant generatedAt,
        TuningSnapshot.Budget budget,
        List<Component> components,
        List<Evaluation> evaluations,
        List<Rec> recommendations,
        List<Verdict> verdicts,
        List<String> omitted) {

    static final String FORMAT = "springtune-report/1";

    /** Recommendation feedback issue form; field ids {@code version}, {@code boot}, {@code recommended}, {@code payload}. */
    static final String FEEDBACK_URL =
            "https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml";

    /** Longest feedback URL we generate (GitHub refuses very long URLs); the payload drops evidence beyond it. */
    static final int MAX_URL_LENGTH = 8000;

    static final Pattern RULE_ID = Pattern.compile("^ST-REC-[A-Z]+-\\d{3}$");
    /**
     * The rule ids SpringTune defines. A rule id can be request text (an audited {@code POST /actuator/tuning} by
     * any authenticated caller), so matching {@link #RULE_ID} is not enough: only these are copied.
     */
    static final Set<String> RULES = Set.copyOf(
            Stream.concat(HikariAdvisor.RULES.stream(), Stream.of(ExecutorAdvisor.RULE)).toList());
    static final Pattern VERSION = Pattern.compile("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(-[A-Za-z0-9.]{1,32})?$");
    static final Pattern CHANGE_REF = Pattern.compile("^change-\\d{1,9}$");
    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    /** {@code 12}, {@code 80%}, {@code 15 ms}, {@code 0.5s}. */
    static final Pattern NUMBER_WITH_UNIT = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,9})?( ?(%|ms|s|m|h|d))?$");
    /** {@link Format#duration}: {@code 5m}, {@code 24h}, {@code 1h30m}, {@code 2m30.5s}. */
    static final Pattern DURATION = Pattern.compile("^(?=\\d)(\\d{1,6}h)?(\\d{1,6}m)?(\\d{1,6}(\\.\\d{1,9})?s)?$");
    /** {@code 3 of 10}. */
    static final Pattern COUNT_OF = Pattern.compile("^\\d{1,9} of \\d{1,9}$");

    static final Set<String> SETTINGS = Set.of("maximumPoolSize", "minimumIdle", "connectionTimeout", "idleTimeout",
            "maxLifetime", "leakDetectionThreshold", "corePoolSize", "maxPoolSize", "queueCapacity");

    static final Set<String> EVIDENCE_KEYS = Set.of("window", "samples", "samplesWithWaitingThreads",
            "maxWaitingThreads", "samplesBelowMaximumWhileWaiting", "fewestOpenConnectionsWhileWaiting",
            "acquireTimeouts", "meanConnectionHoldTime", "meanAcquireTime", "slowHoldThreshold", "budgetCap",
            "peakActiveConnections", "samplesWithBacklogAndAllThreadsBusy", "maxQueued", "queueCapacity");

    static final Set<String> METERS = Set.copyOf(HikariAdvisor.HOLD_TIME_METERS);
    static final Set<String> MODES = Set.of("OBSERVE", "ADVISE", "APPLY");
    static final Set<String> AUDIT_ACTIONS = Set.of("apply", "revert", "freeze");
    static final Set<String> VERDICTS = Set.of("OK", "REGRESSED", "INCONCLUSIVE");
    static final Set<String> KINDS = Set.of("hikari", "executor");

    static final List<String> OMITTED = List.of("componentIds", "knobIds", "knobSources", "signals",
            "recommendation.reason", "recommendation.relatedRuleIds", "recommendation.evidence.nonNumeric",
            "applyPlans", "apply.frozenReason", "apply.requiredRole", "apply.guardrails", "apply.knobs", "audit.who",
            "audit.why", "audit.reasons", "audit.evidence", "audit.capacity");

    /**
     * @param ref   pseudonym, e.g. {@code hikari#1}
     * @param kind  {@code hikari} or {@code executor}
     * @param knobs setting name to current value
     */
    public record Component(String ref, String kind, int samples, Map<String, Long> knobs) {
    }

    /** {@link RuleEvaluation} with the component's pseudonym. */
    public record Evaluation(String component, String ruleId, RuleEvaluation.Status status, Integer seenSamples,
            Integer minSamples, Long seenSeconds, Long neededSeconds, List<String> missingMeters,
            String supersededBy) {
    }

    /**
     * A recommendation without its free-text reason.
     *
     * @param knob        pseudonym and setting, e.g. {@code hikari#1.maximumPoolSize}, or {@code null}
     * @param evidence    the numeric entries of the recommendation's evidence (allowlisted keys only)
     * @param feedbackUrl pre-filled feedback issue for this entry
     */
    public record Rec(String ruleId, String knob, Action action, long currentValue, long recommendedValue,
            Confidence confidence, Map<String, String> evidence, Instant createdAt, String feedbackUrl) {
    }

    /** An apply-mode audit entry without {@code who}, {@code why}, reasons, evidence or the capacity line. */
    public record Verdict(String ref, Instant at, String action, String ruleId, String knob, Map<String, Long> before,
            Map<String, Long> after, ChangeAuditEntry.Outcome outcome, String verdict, String code,
            Boolean capacityObtained, Instant finishedAt) {
    }

    static TuningReport from(TuningSnapshot snapshot) {
        return from(snapshot, starterVersion(), bootVersion(), Runtime.version().feature());
    }

    static TuningReport from(TuningSnapshot snapshot, String springtuneVersion, String springBootVersion,
            int javaVersion) {
        String starter = version(springtuneVersion);
        String boot = version(springBootVersion);

        List<TuningSnapshot.Component> sorted = new ArrayList<>(snapshot.components());
        sorted.sort(Comparator.comparing(TuningSnapshot.Component::id));
        Map<String, String> refs = new HashMap<>();
        Map<String, Integer> ordinals = new HashMap<>();
        Map<String, String> knobRefs = new HashMap<>();
        List<Component> components = new ArrayList<>();
        for (TuningSnapshot.Component component : sorted) {
            String kind = kind(component.id());
            if (kind == null || refs.containsKey(component.id())) {
                continue;
            }
            String ref = kind + "#" + ordinals.merge(kind, 1, Integer::sum);
            refs.put(component.id(), ref);
            Map<String, Long> knobs = new LinkedHashMap<>();
            for (Knob knob : component.knobs()) {
                if (in(SETTINGS, knob.name()) && component.id().equals(knob.component())) {
                    knobs.put(knob.name(), knob.currentValue());
                    knobRefs.putIfAbsent(knob.id(), ref + "." + knob.name());
                }
            }
            components.add(new Component(ref, kind, component.samples(), knobs));
        }

        List<Evaluation> evaluations = new ArrayList<>();
        for (RuleEvaluation rule : snapshot.evaluations()) {
            String ref = refs.get(rule.component());
            if (ref == null || !rule(rule.ruleId()) || rule.status() == null) {
                continue;
            }
            List<String> meters = rule.missingMeters() == null ? null
                    : rule.missingMeters().stream().filter(meter -> in(METERS, meter)).toList();
            evaluations.add(new Evaluation(ref, rule.ruleId(), rule.status(), rule.seenSamples(), rule.minSamples(),
                    rule.seenSeconds(), rule.neededSeconds(), meters,
                    rule(rule.supersededBy()) ? rule.supersededBy() : null));
        }

        List<Rec> recommendations = new ArrayList<>();
        for (Recommendation rec : snapshot.recommendations()) {
            if (!rule(rec.ruleId())) {
                continue;
            }
            Map<String, String> evidence = new LinkedHashMap<>();
            rec.evidence().forEach((key, value) -> {
                if (in(EVIDENCE_KEYS, key) && numeric(value)) {
                    evidence.put(key, value);
                }
            });
            Rec entry = new Rec(rec.ruleId(), knobRefs.get(rec.knobId()), rec.action(), rec.currentValue(),
                    rec.recommendedValue(), rec.confidence(), evidence, rec.createdAt(), null);
            recommendations.add(new Rec(entry.ruleId(), entry.knob(), entry.action(), entry.currentValue(),
                    entry.recommendedValue(), entry.confidence(), entry.evidence(), entry.createdAt(),
                    feedbackUrl(entry, starter, boot)));
        }

        List<Verdict> verdicts = new ArrayList<>();
        if (snapshot.apply() != null) {
            for (ChangeAuditEntry entry : snapshot.apply().audit()) {
                verdicts.add(new Verdict(matches(CHANGE_REF, entry.id()) ? entry.id() : null, entry.at(),
                        in(AUDIT_ACTIONS, entry.action()) ? entry.action() : null,
                        rule(entry.ruleId()) ? entry.ruleId() : null,
                        entry.knobId() == null ? null : knobRefs.get(entry.knobId()), settings(entry.before()),
                        settings(entry.after()), entry.outcome(),
                        in(VERDICTS, entry.verdict()) ? entry.verdict() : null,
                        matches(CODE, entry.code()) ? entry.code() : null, entry.capacityObtained(),
                        entry.finishedAt()));
            }
        }

        TuningSnapshot.Budget budget = snapshot.budget();
        return new TuningReport(FORMAT, starter, boot, javaVersion,
                in(MODES, snapshot.mode()) ? snapshot.mode() : null, snapshot.generatedAt(),
                budget == null ? null : new TuningSnapshot.Budget(budget.databaseMaxConnections(),
                        budget.maxReplicas(), budget.safetyFactor(), budget.perInstanceCap()),
                components, evaluations, recommendations, verdicts, OMITTED);
    }

    private static boolean rule(String ruleId) {
        return matches(RULE_ID, ruleId) && RULES.contains(ruleId);
    }

    private static String kind(String componentId) {
        int colon = componentId == null ? -1 : componentId.indexOf(':');
        String kind = colon > 0 ? componentId.substring(0, colon) : null;
        return in(KINDS, kind) ? kind : null;
    }

    private static Map<String, Long> settings(Map<String, Long> values) {
        Map<String, Long> copy = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (in(SETTINGS, key) && value != null) {
                    copy.put(key, value);
                }
            });
        }
        return copy;
    }

    static boolean numeric(String value) {
        return matches(NUMBER_WITH_UNIT, value) || matches(DURATION, value) || matches(COUNT_OF, value);
    }

    /** Null-safe membership ({@code Set.of(...).contains(null)} throws). */
    private static boolean in(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static String version(String value) {
        return matches(VERSION, value) ? value : "unknown";
    }

    /** {@code version} of the starter from its filtered resource, or {@code unknown}. */
    static String starterVersion() {
        try (InputStream in = TuningReport.class.getResourceAsStream("springtune-starter-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return version(properties.getProperty("version"));
        } catch (IOException | IllegalArgumentException ex) {
            return "unknown";
        }
    }

    static String bootVersion() {
        try {
            return version(SpringBootVersion.getVersion());
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    /**
     * The feedback form, pre-filled by field id: {@code version}, {@code boot}, {@code recommended} and
     * {@code payload} (this entry as JSON, without the URL). Built only from the entry's allowlisted values.
     */
    static String feedbackUrl(Rec entry, String starter, String boot) {
        String title = "[recommendation] " + entry.ruleId() + ": " + entry.action() + " " + entry.currentValue()
                + " to " + entry.recommendedValue();
        String url = feedbackUrl(title, starter, boot, entry, entry.evidence());
        return url.length() <= MAX_URL_LENGTH ? url : feedbackUrl(title, starter, boot, entry, Map.of());
    }

    private static String feedbackUrl(String title, String starter, String boot, Rec entry,
            Map<String, String> evidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", entry.ruleId());
        payload.put("knob", entry.knob());
        payload.put("action", entry.action());
        payload.put("currentValue", entry.currentValue());
        payload.put("recommendedValue", entry.recommendedValue());
        payload.put("confidence", entry.confidence());
        payload.put("evidence", evidence);
        payload.put("createdAt", entry.createdAt());
        return FEEDBACK_URL + "&title=" + encode(title) + "&version=" + encode(starter) + "&boot=" + encode(boot)
                + "&recommended=" + entry.recommendedValue() + "&payload=" + encode(json(payload));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Minimal JSON for the payload parameter: strings, numbers, enums, instants, maps; nothing else occurs. */
    static String json(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else {
            String text = value.toString();
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') {
                    out.append('\\').append(c);
                } else if (c < 0x20) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            out.append('"');
        }
    }
}
