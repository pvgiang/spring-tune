package io.github.winkytek.springtune.limiter;

import java.time.Duration;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** WebFlux on Reactor Netty with the same fake downstream (capacity 10) as the servlet tests. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ReactiveTestApplication.ReactiveController.class)
class ReactiveTestApplication {

    @Bean
    ServletTestApplication.FakeDownstream fakeDownstream() {
        return new ServletTestApplication.FakeDownstream();
    }

    @RestController
    static class ReactiveController {

        private final ServletTestApplication.FakeDownstream downstream;

        ReactiveController(ServletTestApplication.FakeDownstream downstream) {
            this.downstream = downstream;
        }

        @GetMapping("/work")
        Mono<String> work() {
            return Mono.fromCallable(downstream::call).subscribeOn(Schedulers.boundedElastic());
        }

        @GetMapping("/slow")
        Mono<String> slow() {
            return Mono.delay(Duration.ofSeconds(30)).thenReturn("late");
        }
    }

    /**
     * Starts the app on Netty. Tomcat is on the test classpath too (servlet tests), and Boot would prefer it for a
     * reactive app; registering the Netty factory up front makes Boot's own factory configurations back off. The
     * factory class moved between Boot 3 and 4, hence the lookup by name.
     */
    static ConfigurableApplicationContext start(String... properties) {
        ApplicationContextInitializer<ConfigurableApplicationContext> netty = context -> {
            String name = ClassUtils.isPresent("org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory",
                    null) ? "org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory"
                            : "org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory";
            ((BeanDefinitionRegistry) context).registerBeanDefinition("nettyReactiveWebServerFactory",
                    new RootBeanDefinition(ClassUtils.resolveClassName(name, null)));
        };
        return new SpringApplicationBuilder(ReactiveTestApplication.class).web(WebApplicationType.REACTIVE)
                .initializers(netty).properties(properties).run();
    }

    static String webServerType(ConfigurableApplicationContext context) throws ReflectiveOperationException {
        return context.getClass().getMethod("getWebServer").invoke(context).getClass().getSimpleName();
    }
}
