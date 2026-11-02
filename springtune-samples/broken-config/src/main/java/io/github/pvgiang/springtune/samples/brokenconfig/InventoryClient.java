package io.github.pvgiang.springtune.samples.brokenconfig;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Built without timeouts (ST-HTTP-001): a hanging inventory service would hang request threads. */
@Component
class InventoryClient {

    private final RestTemplate rest = new RestTemplate();

    String stock(String sku) {
        return rest.getForObject("http://inventory/stock/{sku}", String.class, sku);
    }
}
