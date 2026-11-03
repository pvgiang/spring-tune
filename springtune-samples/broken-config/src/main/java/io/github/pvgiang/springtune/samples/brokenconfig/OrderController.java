package io.github.pvgiang.springtune.samples.brokenconfig;

import java.util.Map;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    OrderController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Runs a query and keeps the connection for {@code holdMs}, simulating a query (or a transaction) of that
     * duration. Use small values to show "raise the pool", large ones to show "do not raise the pool".
     */
    @GetMapping("/orders")
    Map<String, Object> orders(@RequestParam(defaultValue = "20") long holdMs) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            connection.createStatement().execute("SELECT 1");
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        audit.record("orders viewed");
        return Map.of("status", "ok", "heldMs", holdMs);
    }
}
