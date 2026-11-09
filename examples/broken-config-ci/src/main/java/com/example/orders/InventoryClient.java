package com.example.orders;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Calls the inventory service. Risky on purpose: see README.md, ST-HTTP-001. */
@Component
class InventoryClient {

    private final RestTemplate rest = new RestTemplate();

    String stock(String sku) {
        return rest.getForObject("http://inventory:8080/stock/{sku}", String.class, sku);
    }
}
