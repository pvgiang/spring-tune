package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import io.github.winkytek.springtune.model.Recommendation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks for {@code GET /actuator/tuning/report} output, independent of the Jackson version: a minimal JSON parser,
 * the trap check (no hostile fixture string survives, also not inside a decoded feedback URL) and the schema check
 * (position-aware: every path is one the report defines and every leaf holds that path's class of value).
 */
final class ReportChecks {

    /** Substrings of the hostile fixtures; none may appear in a report. */
    static final List<String> FORBIDDEN = List.of("orders", "prod.internal", ".internal", "jdbc:", "postgresql",
            "10.1.2.3", "5432", "alice", "corp.example", "@", "ACME-123", "deploy", "db.prod.acme", "password",
            "orders_svc", "refused", "authentication", "Connection to", "ORDERSDB");

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    // The grammar, written independently of TuningReport's own patterns: one class of value per JSON path.
    private static final Pattern INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");
    private static final Pattern RULE = Pattern.compile("^ST-REC-(HIKARI-00[1-4]|EXEC-001)$"); // only SpringTune's
    private static final Pattern COMPONENT = Pattern.compile("^(hikari|executor)#[1-9]\\d*$");
    private static final Pattern VERSION = Pattern.compile("^(\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?|unknown)$");
    private static final Pattern EVIDENCE_VALUE = Pattern.compile("^(-?\\d+(\\.\\d+)?( ?(%|ms|s|m|h|d))?" // number + unit
            + "|(?=\\d)(\\d+h)?(\\d+m)?(\\d+(\\.\\d+)?s)?" // duration
            + "|\\d+ of \\d+)$"); // count of total
    private static final Set<String> SETTINGS = Set.of("maximumPoolSize", "minimumIdle", "connectionTimeout",
            "idleTimeout", "maxLifetime", "leakDetectionThreshold", "corePoolSize", "maxPoolSize", "queueCapacity");
    private static final Set<String> OMITTED = Set.of("componentIds", "knobIds", "knobSources", "signals",
            "recommendation.reason", "recommendation.relatedRuleIds", "recommendation.evidence.nonNumeric",
            "applyPlans", "apply.frozenReason", "apply.requiredRole", "apply.guardrails", "apply.knobs", "audit.who",
            "audit.why", "audit.reasons", "audit.evidence", "audit.capacity");
    private static final Set<String> EVIDENCE_KEYS = Set.of("window", "samples", "samplesWithWaitingThreads",
            "maxWaitingThreads", "samplesBelowMaximumWhileWaiting", "fewestOpenConnectionsWhileWaiting",
            "acquireTimeouts", "meanConnectionHoldTime", "meanAcquireTime", "slowHoldThreshold", "budgetCap",
            "peakActiveConnections", "samplesWithBacklogAndAllThreadsBusy", "maxQueued", "queueCapacity");
    private static final String FEEDBACK =
            "https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml&";
    /** The longest feedback URL the report generates ({@code TuningReport.MAX_URL_LENGTH}). */
    static final int MAX_FEEDBACK_URL = 8000;
    private static final Pattern TITLE = Pattern.compile(
            "^\\[recommendation\\] ST-REC-(HIKARI-00[1-4]|EXEC-001): (INCREASE|DECREASE|HOLD) \\d+ to \\d+$");

    /** A class of leaf value: what a JSON path may hold (besides {@code null}). */
    private record Leaf(String name, Predicate<Object> test) {
    }

    private static Leaf pattern(String name, Pattern pattern) {
        return new Leaf(name, v -> v instanceof String text && pattern.matcher(text).matches());
    }

    private static Leaf oneOf(String name, Set<String> values) {
        return new Leaf(name, v -> v instanceof String text && values.contains(text));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }

    private static final Leaf NUMBER = new Leaf("a number", v -> v instanceof Double);
    private static final Leaf BOOLEAN = new Leaf("a boolean", v -> v instanceof Boolean);
    private static final Leaf AN_INSTANT = pattern("an instant", INSTANT);
    private static final Leaf A_RULE = pattern("a SpringTune rule id", RULE);
    private static final Leaf A_COMPONENT = pattern("a component pseudonym", COMPONENT);
    private static final Leaf A_KNOB = new Leaf("a knob pseudonym", v -> v instanceof String text
            && text.indexOf('.') > 0 && COMPONENT.matcher(text.substring(0, text.indexOf('.'))).matches()
            && SETTINGS.contains(text.substring(text.indexOf('.') + 1)));
    private static final Leaf A_VERSION = pattern("a version", VERSION);
    private static final Leaf ACTION = oneOf("a recommendation action", names(Recommendation.Action.values()));
    private static final Leaf CONFIDENCE = oneOf("a confidence", names(Recommendation.Confidence.values()));
    private static final Leaf EVIDENCE = pattern("a number with an optional unit", EVIDENCE_VALUE);

    /** Leaf class per normalized path ({@code []} for list items, {@code *} for a setting or evidence key). */
    private static final Map<String, Leaf> LEAVES = new LinkedHashMap<>();
    /** Paths that hold objects or lists. */
    private static final Set<String> CONTAINERS = Set.of("$", "$.budget", "$.components", "$.components[]",
            "$.components[].knobs", "$.evaluations", "$.evaluations[]", "$.evaluations[].missingMeters",
            "$.recommendations", "$.recommendations[]", "$.recommendations[].evidence", "$.verdicts", "$.verdicts[]",
            "$.verdicts[].before", "$.verdicts[].after", "$.omitted", "$.payload", "$.payload.evidence");
    /** Objects keyed by setting name, and objects keyed by evidence key. */
    private static final Set<String> SETTING_MAPS = Set.of("$.components[].knobs", "$.verdicts[].before",
            "$.verdicts[].after");
    private static final Set<String> EVIDENCE_MAPS = Set.of("$.recommendations[].evidence", "$.payload.evidence");

    static {
        LEAVES.put("$.format", oneOf("the report format", Set.of("springtune-report/1")));
        LEAVES.put("$.springtuneVersion", A_VERSION);
        LEAVES.put("$.springBootVersion", A_VERSION);
        LEAVES.put("$.javaVersion", NUMBER);
        LEAVES.put("$.mode", oneOf("a mode", Set.of("OBSERVE", "ADVISE", "APPLY")));
        LEAVES.put("$.generatedAt", AN_INSTANT);
        for (String field : List.of("databaseMaxConnections", "maxReplicas", "safetyFactor", "perInstanceCap")) {
            LEAVES.put("$.budget." + field, NUMBER);
        }
        LEAVES.put("$.components[].ref", A_COMPONENT);
        LEAVES.put("$.components[].kind", oneOf("a component kind", Set.of("hikari", "executor")));
        LEAVES.put("$.components[].samples", NUMBER);
        LEAVES.put("$.components[].knobs.*", NUMBER);
        LEAVES.put("$.evaluations[].component", A_COMPONENT);
        LEAVES.put("$.evaluations[].ruleId", A_RULE);
        LEAVES.put("$.evaluations[].status", oneOf("a rule status", names(RuleEvaluation.Status.values())));
        for (String field : List.of("seenSamples", "minSamples", "seenSeconds", "neededSeconds")) {
            LEAVES.put("$.evaluations[]." + field, NUMBER);
        }
        LEAVES.put("$.evaluations[].missingMeters[]", oneOf("a meter name",
                Set.of("hikaricp.connections.acquire", "hikaricp.connections.usage")));
        LEAVES.put("$.evaluations[].supersededBy", A_RULE);
        for (String prefix : List.of("$.recommendations[]", "$.payload")) {
            LEAVES.put(prefix + ".ruleId", A_RULE);
            LEAVES.put(prefix + ".knob", A_KNOB);
            LEAVES.put(prefix + ".action", ACTION);
            LEAVES.put(prefix + ".currentValue", NUMBER);
            LEAVES.put(prefix + ".recommendedValue", NUMBER);
            LEAVES.put(prefix + ".confidence", CONFIDENCE);
            LEAVES.put(prefix + ".evidence.*", EVIDENCE);
            LEAVES.put(prefix + ".createdAt", AN_INSTANT);
        }
        LEAVES.put("$.recommendations[].feedbackUrl", new Leaf("a feedback URL", v -> v instanceof String));
        LEAVES.put("$.verdicts[].ref", pattern("an audit ref", Pattern.compile("^change-[1-9]\\d*$")));
        LEAVES.put("$.verdicts[].at", AN_INSTANT);
        LEAVES.put("$.verdicts[].action", oneOf("an audit action", Set.of("apply", "revert", "freeze")));
        LEAVES.put("$.verdicts[].ruleId", A_RULE);
        LEAVES.put("$.verdicts[].knob", A_KNOB);
        LEAVES.put("$.verdicts[].before.*", NUMBER);
        LEAVES.put("$.verdicts[].after.*", NUMBER);
        LEAVES.put("$.verdicts[].outcome", oneOf("an audit outcome", names(ChangeAuditEntry.Outcome.values())));
        LEAVES.put("$.verdicts[].verdict", oneOf("a verification verdict", Set.of("OK", "REGRESSED", "INCONCLUSIVE")));
        LEAVES.put("$.verdicts[].code", oneOf("a refusal code", names(ApplyGuard.Code.values())));
        LEAVES.put("$.verdicts[].capacityObtained", BOOLEAN);
        LEAVES.put("$.verdicts[].finishedAt", AN_INSTANT);
        LEAVES.put("$.omitted[]", oneOf("an omitted category", OMITTED));
    }

    private ReportChecks() {
    }

    /** Trap: none of the hostile strings, no IPv4 address, also inside every URL-decoded feedback URL. */
    static void assertNoHostileValue(String body) {
        List<String> texts = new ArrayList<>();
        texts.add(body);
        feedbackUrls(parse(body), texts);
        for (String text : texts) {
            for (String forbidden : FORBIDDEN) {
                if (forbidden.chars().allMatch(Character::isDigit)) {
                    // A number (the port) only as a number of its own: its digits also occur inside timestamps.
                    assertThat(Pattern.compile("(?<!\\d)" + forbidden + "(?!\\d)").matcher(text).find())
                            .as("report must not contain %s", forbidden).isFalse();
                } else {
                    assertThat(text).as("report must not contain %s", forbidden).doesNotContain(forbidden);
                }
            }
            assertThat(IPV4.matcher(text).find()).as("IPv4 address in %s", text).isFalse();
        }
    }

    private static void feedbackUrls(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("feedbackUrl".equals(key) && value instanceof String url) {
                    out.add(URLDecoder.decode(url, StandardCharsets.UTF_8));
                } else {
                    feedbackUrls(value, out);
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(item -> feedbackUrls(item, out));
        }
    }

    /**
     * Schema: every path is one the report defines, and every leaf holds the class of value defined for its path
     * (a specific enum set, a number, an instant, a known rule id, a setting name, a pseudonym), not merely some token
     * of the grammar; returns the number of leaves checked.
     */
    static int assertSchema(String body) {
        Object root = parse(body);
        assertThat(root).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) root).get("format")).isEqualTo("springtune-report/1");
        return walk(root, "$");
    }

    private static int walk(Object node, String path) {
        int leaves = 0;
        if (node instanceof Map<?, ?> map) {
            assertThat(CONTAINERS).as("object at %s, which is not an object path of the report", path)
                    .contains(path);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                assertThat(key).as("key at %s", path).matches("^[a-zA-Z]+$");
                String child = path + "." + key;
                if (SETTING_MAPS.contains(path)) {
                    assertThat(SETTINGS).as("setting at %s", path).contains(key);
                    child = path + ".*";
                } else if (EVIDENCE_MAPS.contains(path)) {
                    assertThat(EVIDENCE_KEYS).as("evidence key at %s", path).contains(key);
                    child = path + ".*";
                }
                leaves += walk(entry.getValue(), child);
            }
        } else if (node instanceof List<?> list) {
            assertThat(CONTAINERS).as("list at %s, which is not a list path of the report", path).contains(path);
            for (Object item : list) {
                leaves += walk(item, path + "[]");
            }
        } else if (node == null && CONTAINERS.contains(path)) {
            return 0; // an absent list or object (e.g. missingMeters of a rule that is not NO_SIGNAL)
        } else {
            leaves++;
            Leaf leaf = LEAVES.get(path);
            assertThat(leaf).as("leaf at %s, which is not a path of the report", path).isNotNull();
            if (node != null) {
                assertThat(leaf.test().test(node)).as("leaf %s = \"%s\" is not %s", path, node, leaf.name()).isTrue();
                if (path.equals("$.recommendations[].feedbackUrl")) {
                    assertFeedbackUrl((String) node);
                }
            }
        }
        return leaves;
    }

    private static void assertFeedbackUrl(String url) {
        assertThat(url).startsWith(FEEDBACK);
        assertThat(url.length()).isLessThanOrEqualTo(MAX_FEEDBACK_URL);
        for (String parameter : url.substring(FEEDBACK.length()).split("&")) {
            String[] pair = parameter.split("=", 2);
            String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            switch (pair[0]) {
                case "title" -> assertThat(value).matches(TITLE);
                case "version", "boot" -> assertThat(value).matches(VERSION);
                case "recommended" -> assertThat(value).matches("^\\d+$");
                case "payload" -> walk(parse(value), "$.payload");
                default -> throw new AssertionError("unexpected feedback URL parameter " + pair[0]);
            }
        }
    }

    // --- minimal JSON parser (objects, arrays, strings, numbers, true/false/null) ---

    static Object parse(String json) {
        int[] at = {0};
        Object value = value(json, at);
        skip(json, at);
        assertThat(at[0]).as("trailing content").isEqualTo(json.length());
        return value;
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) {
            at[0]++;
        }
    }

    private static Object value(String s, int[] at) {
        skip(s, at);
        char c = s.charAt(at[0]);
        if (c == '{') {
            Map<String, Object> map = new LinkedHashMap<>();
            at[0]++;
            skip(s, at);
            if (s.charAt(at[0]) == '}') {
                at[0]++;
                return map;
            }
            while (true) {
                skip(s, at);
                String key = string(s, at);
                skip(s, at);
                expect(s, at, ':');
                map.put(key, value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') {
                    at[0]++;
                    continue;
                }
                expect(s, at, '}');
                return map;
            }
        }
        if (c == '[') {
            List<Object> list = new ArrayList<>();
            at[0]++;
            skip(s, at);
            if (s.charAt(at[0]) == ']') {
                at[0]++;
                return list;
            }
            while (true) {
                list.add(value(s, at));
                skip(s, at);
                if (s.charAt(at[0]) == ',') {
                    at[0]++;
                    continue;
                }
                expect(s, at, ']');
                return list;
            }
        }
        if (c == '"') {
            return string(s, at);
        }
        for (String literal : List.of("true", "false", "null")) {
            if (s.startsWith(literal, at[0])) {
                at[0] += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) {
            at[0]++;
        }
        assertThat(at[0]).as("number at %s", start).isGreaterThan(start);
        return Double.valueOf(s.substring(start, at[0]));
    }

    private static void expect(String s, int[] at, char c) {
        assertThat(s.charAt(at[0])).as("JSON at %s", at[0]).isEqualTo(c);
        at[0]++;
    }

    private static String string(String s, int[] at) {
        expect(s, at, '"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = s.charAt(at[0]++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char e = s.charAt(at[0]++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16));
                        at[0] += 4;
                    }
                    default -> out.append(e);
                }
            } else {
                out.append(c);
            }
        }
    }
}
