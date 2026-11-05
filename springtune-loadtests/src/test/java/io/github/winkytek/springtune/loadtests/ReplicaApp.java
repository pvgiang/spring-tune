package io.github.winkytek.springtune.loadtests;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One replica: a plain Spring Boot servlet application with the starter, actuator, Spring Security (HTTP basic,
 * operator {@code ops} with role {@code TUNER}) and a Hikari pool on PostgreSQL. Everything else comes from the
 * properties {@link Replica} passes.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class ReplicaApp {

    static final String OPERATOR = "ops";
    static final String OPERATOR_PASSWORD = "ops-pw";

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.anyRequest().hasRole("TUNER"))
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(
                User.withUsername(OPERATOR).password("{noop}" + OPERATOR_PASSWORD).roles("TUNER").build());
    }
}
