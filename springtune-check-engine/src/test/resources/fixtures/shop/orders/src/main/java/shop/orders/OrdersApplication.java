package shop.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

@EnableAsync
@SpringBootApplication
public class OrdersApplication {

    private final RestTemplate inventory = new RestTemplate();

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
