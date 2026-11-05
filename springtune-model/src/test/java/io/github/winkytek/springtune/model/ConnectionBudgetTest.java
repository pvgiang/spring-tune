package io.github.winkytek.springtune.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionBudgetTest {

    @Test
    void usableConnectionsAppliesSafetyFactorAndFloors() {
        assertThat(new ConnectionBudget(100, 0.8).usableConnections()).isEqualTo(80);
        assertThat(new ConnectionBudget(101, 0.8).usableConnections()).isEqualTo(80);
        assertThat(new ConnectionBudget(100, 1.0).usableConnections()).isEqualTo(100);
    }

    @Test
    void demandAboveUsableIsExceeded() {
        ConnectionBudget budget = new ConnectionBudget(100, 0.8);
        var evaluation = budget.evaluate(List.of(
                new ConnectionBudget.Consumer("orders", 20, 3),
                new ConnectionBudget.Consumer("billing", 10, 3)));
        assertThat(evaluation.demand()).isEqualTo(90);
        assertThat(evaluation.exceeded()).isTrue();
    }

    @Test
    void demandEqualToUsableIsNotExceeded() {
        var evaluation = new ConnectionBudget(100, 0.8)
                .evaluate(List.of(new ConnectionBudget.Consumer("orders", 20, 4)));
        assertThat(evaluation.demand()).isEqualTo(80);
        assertThat(evaluation.exceeded()).isFalse();
    }

    @Test
    void perInstanceCapDividesUsableByReplicas() {
        assertThat(new ConnectionBudget(100, 0.8).perInstanceCap(6)).isEqualTo(13);
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> new ConnectionBudget(0, 0.8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBudget(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBudget(10, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionBudget(10, 0.8).perInstanceCap(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
