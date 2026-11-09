package com.example.orders;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    private final JdbcTemplate jdbc;
    private final InventoryClient inventory;

    OrderController(JdbcTemplate jdbc, InventoryClient inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    @GetMapping("/orders/count")
    Map<String, Object> count() {
        return Map.of("orders", jdbc.queryForObject("select count(*) from information_schema.tables", Long.class));
    }

    @GetMapping("/stock/{sku}")
    String stock(@PathVariable String sku) {
        return inventory.stock(sku);
    }
}
