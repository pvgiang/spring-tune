package io.github.winkytek.springtune.loadtests;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * What the human operator does, by polling {@code GET /actuator/tuning}. Generous ceilings, never a sleep that a
 * result depends on, and no retry of a request that was refused: a refusal is a result.
 */
final class Operator {

    static final Duration RECOMMENDATION_CEILING = Duration.ofSeconds(90);
    static final Duration VERIFICATION_CEILING = Duration.ofSeconds(120);

    private Operator() {
    }

    /** Waits for a recommendation for the pool that satisfies {@code condition} and returns it. */
    static JsonNode awaitRecommendation(Replica replica, Predicate<JsonNode> condition, String what) {
        AtomicReference<JsonNode> found = new AtomicReference<>();
        await(replica.name() + ": " + what).atMost(RECOMMENDATION_CEILING).pollInterval(Duration.ofMillis(500))
                .ignoreExceptions().until(() -> {
                    Optional<JsonNode> rec = replica.recommendation();
                    rec.filter(condition).ifPresent(found::set);
                    return found.get() != null;
                });
        return found.get();
    }

    static JsonNode awaitAnyRecommendation(Replica replica) {
        return awaitRecommendation(replica, rec -> true, "a recommendation for " + Replica.KNOB);
    }

    static Predicate<JsonNode> action(String action) {
        return rec -> action.equals(rec.path("action").asText());
    }

    /** Waits until the change has a final outcome (not VERIFYING) and returns its audit entry. */
    static JsonNode awaitFinal(Replica replica, String changeId) {
        AtomicReference<JsonNode> found = new AtomicReference<>();
        await(replica.name() + ": final outcome of " + changeId).atMost(VERIFICATION_CEILING)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(() -> {
                    Optional<JsonNode> entry = replica.auditEntry(changeId);
                    entry.filter(e -> !"VERIFYING".equals(e.path("outcome").asText())).ifPresent(found::set);
                    return found.get() != null;
                });
        return found.get();
    }
}
