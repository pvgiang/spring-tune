package io.github.winkytek.springtune.model;

import java.util.Objects;

/**
 * Why a finding was accepted by the team. A reason is mandatory: silent suppressions erode trust and
 * make the suppress-rate signal (plan Section 11) meaningless.
 */
public record Suppression(String reason) {

    public Suppression {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Suppression reason must not be blank");
        }
    }
}
