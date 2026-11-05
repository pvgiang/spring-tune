package io.github.winkytek.springtune.samples.brokenconfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Deliberately risky demo app. {@code springtune check} flags its configuration; {@code /actuator/tuning}
 * shows live knobs and recommendations when {@code /orders} is put under load.
 */
@EnableAsync
@SpringBootApplication
public class BrokenConfigApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokenConfigApplication.class, args);
    }
}
