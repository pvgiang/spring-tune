package io.github.winkytek.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks for {@code GET /actuator/tuning/report} output, independent of the Jackson version: a minimal JSON parser,
 * the trap check (no hostile fixture string survives, also not inside a decoded feedback URL) and the schema check
 * (every string leaf and every key matches the report's grammar).
 */
final class ReportChecks {

    /** Substrings of the hostile fixtures; none may appear in a report. */
    static final List<String> FORBIDDEN = List.of("orders", "prod.internal", ".internal", "jdbc:", "postgresql",
            "10.1.2.3", "5432", "alice", "corp.example", "@", "ACME-123", "deploy", "db.prod.acme", "password",
            "orders_svc", "refused", "authentication", "Connection to", "ORDERSDB");

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    // The grammar, written independently of TuningReport's own patterns.
    private static final List<Pattern> LEAVES = List.of(
            Pattern.compile("^[A-Z][A-Z_]*$"), // enum names and refusal codes
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$"), // ISO instant
            Pattern.compile("^ST-REC-(HIKARI-00[1-4]|EXEC-001)$"), // rule id: only rules SpringTune defines
            Pattern.compile("^(hikari|executor)#\\d+(\\.[a-zA-Z]+)?$"), // pseudonym
            Pattern.compile("^(hikari|executor)$"), // kind
            Pattern.compile("^(apply|revert|freeze)$"), // audit action
            Pattern.compile("^change-\\d+$"), // audit ref
            Pattern.compile("^hikaricp\\.connections\\.(acquire|usage)$"), // meter names
            Pattern.compile("^(\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?|unknown)$"), // versions
            Pattern.compile("^-?\\d+(\\.\\d+)?( ?(%|ms|s|m|h|d))?$"), // number with unit
            Pattern.compile("^(?=\\d)(\\d+h)?(\\d+m)?(\\d+(\\.\\d+)?s)?$"), // duration
            Pattern.compile("^\\d+ of \\d+$"), // count of total
            Pattern.compile("^springtune-report/1$"));
    private static final Set<String> SETTINGS = Set.of("maximumPoolSize", "minimumIdle", "connectionTimeout",
            "idleTimeout", "maxLifetime", "leakDetectionThreshold", "corePoolSize", "maxPoolSize", "queueCapacity");
    private static final Set<String> OMITTED = Set.of("componentIds", "knobIds", "knobSources", "signals",
            "recommendation.reason", "recommendation.relatedRuleIds", "recommendation.evidence.nonNumeric",
            "applyPlans", "apply.frozenReason", "apply.requiredRole", "apply.guardrails", "apply.knobs", "audit.who",
            "audit.why", "audit.reasons", "audit.evidence", "audit.capacity");
    private static final String FEEDBACK =
            "https://github.com/pvgiang/spring-tune/issues/new?template=3-recommendation-feedback.yml&";
    private static final Pattern TITLE = Pattern.compile(
            "^\\[recommendation\\] ST-REC-[A-Z]+-\\d{3}: (INCREASE|DECREASE|HOLD) \\d+ to \\d+$");

    private ReportChecks() {
    }

    /** Trap: none of the hostile strings, no IPv4 address, also inside every URL-decoded feedback URL. */
    static void assertNoHostileValue(String body) {
        List<String> texts = new ArrayList<>();
        texts.add(body);
        feedbackUrls(parse(body), texts);
        for (String text : texts) {
            for (String forbidden : FORBIDDEN) {
                assertThat(text).as("report must not contain %s", forbidden).doesNotContain(forbidden);
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

    /** Schema: every key is a plain identifier, every string leaf is in the grammar; returns the leaves checked. */
    static int assertSchema(String body) {
        Object root = parse(body);
        assertThat(root).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) root).get("format")).isEqualTo("springtune-report/1");
        return walk(root, "$");
    }

    private static int walk(Object node, String path) {
        int leaves = 0;
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                assertThat(key).as("key at %s", path).matches("^[a-zA-Z]+$");
                if (path.endsWith(".knobs") || path.endsWith(".before") || path.endsWith(".after")) {
                    assertThat(SETTINGS).as("setting at %s", path).contains(key);
                }
                leaves += walk(entry.getValue(), path + "." + key);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                leaves += walk(item, path + "[]");
            }
        } else if (node instanceof String text) {
            leaves++;
            if (path.equals("$.omitted[]")) {
                assertThat(OMITTED).as("omitted category").contains(text);
            } else if (path.endsWith(".feedbackUrl")) {
                assertFeedbackUrl(text);
            } else {
                assertThat(LEAVES.stream().anyMatch(p -> p.matcher(text).matches()))
                        .as("string leaf %s = \"%s\" is outside the report grammar", path, text).isTrue();
            }
        }
        return leaves;
    }

    private static void assertFeedbackUrl(String url) {
        assertThat(url).startsWith(FEEDBACK);
        assertThat(url.length()).isLessThanOrEqualTo(8192);
        for (String parameter : url.substring(FEEDBACK.length()).split("&")) {
            String[] pair = parameter.split("=", 2);
            String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            switch (pair[0]) {
                case "title" -> assertThat(value).matches(TITLE);
                case "version", "boot" -> assertThat(value).matches(LEAVES.get(8));
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
