package smoke.springtune;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.winkytek.springtune.boot.TuningService;
import io.github.winkytek.springtune.limiter.AdaptiveLimiter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** The published starter and limiter auto-configure in a plain Boot application. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeApplicationTest {

    @Autowired
    Environment environment;

    @Autowired
    TuningService tuning;

    @Autowired
    AdaptiveLimiter limiter;

    @Test
    void starterAndLimiterWork() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + environment.getProperty("local.server.port");

        HttpResponse<String> hello = client.send(HttpRequest.newBuilder(URI.create(base + "/hello")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(hello.statusCode()).isEqualTo(200);
        assertThat(hello.body()).isEqualTo("hello 1");
        assertThat(limiter.count(AdaptiveLimiter.Decision.ACCEPTED)).isGreaterThanOrEqualTo(1);

        tuning.sample();
        HttpResponse<String> snapshot = client.send(
                HttpRequest.newBuilder(URI.create(base + "/actuator/tuning")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(snapshot.statusCode()).isEqualTo(200);
        assertThat(snapshot.body()).contains("\"mode\"").contains("hikari:");
    }
}
