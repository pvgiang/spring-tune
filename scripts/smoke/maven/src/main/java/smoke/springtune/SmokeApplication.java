package smoke.springtune;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class SmokeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmokeApplication.class, args);
    }

    @RestController
    static class HelloController {

        private final JdbcTemplate jdbc;

        HelloController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** Touches the database so the Hikari pool starts and shows up in /actuator/tuning. */
        @GetMapping("/hello")
        String hello() {
            return "hello " + jdbc.queryForObject("SELECT 1", Integer.class);
        }
    }
}
