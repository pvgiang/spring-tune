package shop.payments;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
class GatewayClient {

    private final WebClient client = WebClient.create("https://psp.example.com");
}
