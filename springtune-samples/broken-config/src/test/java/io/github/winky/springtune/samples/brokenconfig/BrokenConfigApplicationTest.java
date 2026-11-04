package io.github.winky.springtune.samples.brokenconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** AC-12: the starter works in a real Spring Boot application and the endpoint is read-only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrokenConfigApplicationTest {

    @Autowired
    Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path) throws Exception {
        String port = environment.getProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void tuningEndpointShowsLiveKnobsAndBudget() throws Exception {
        assertThat(send("GET", "/orders").statusCode()).isEqualTo(200);
        HttpResponse<String> response = send("GET", "/actuator/tuning");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"mode\":\"ADVISE\"")
                .contains("\"applySupported\":false")
                .contains("\"applyPlans\":[")
                .contains("\"perInstanceCap\":13")
                .contains("\"id\":\"hikari:orders-pool\"")
                .contains("\"id\":\"hikari.orders-pool.maximumPoolSize\"")
                .contains("\"currentValue\":20")
                .contains("\"id\":\"executor:applicationTaskExecutor\"");
    }

    /** Advise mode (the sample's default): no write operation, whatever the body. */
    @Test
    void tuningEndpointHasNoWriteOperation() throws Exception {
        int status = send("POST", "/actuator/tuning").statusCode();
        assertThat(status).isIn(404, 405);
        String port = environment.getProperty("local.server.port");
        HttpRequest apply = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/tuning"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"apply\",\"ruleId\":\"ST-REC-HIKARI-001\","
                        + "\"knobId\":\"hikari.orders-pool.maximumPoolSize\",\"expectedCurrent\":20,\"reason\":\"x\"}"))
                .build();
        assertThat(http.send(apply, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(404, 405);
    }
}
