package io.github.winky.springtune.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LimiterAutoConfigurationTest {

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LimiterAutoConfiguration.class));

    private final ReactiveWebApplicationContextRunner reactive = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LimiterAutoConfiguration.class));

    @Test
    void offByDefault() {
        servlet.run(context -> assertThat(context).doesNotHaveBean(AdaptiveLimiter.class)
                .doesNotHaveBean(ConcurrencyLimitFilter.class));
        reactive.run(context -> assertThat(context).doesNotHaveBean(AdaptiveLimiter.class)
                .doesNotHaveBean(ConcurrencyLimitWebFilter.class));
    }

    @Test
    void shadowModeWhenOnlyEnabled(CapturedOutput output) {
        servlet.withPropertyValues("springtune.limiter.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AdaptiveLimiter.class).hasSingleBean(ConcurrencyLimitFilter.class)
                    .doesNotHaveBean(ConcurrencyLimitWebFilter.class);
            AdaptiveLimiter limiter = context.getBean(AdaptiveLimiter.class);
            assertThat(limiter.isEnforcing()).isFalse();
            assertThat(limiter.limit()).isEqualTo(20);
            assertThat(limiter.isWarmedUp()).isFalse();
            assertThat(context.getBean(ConcurrencyLimitFilter.class).getOrder())
                    .isEqualTo(ConcurrencyLimitFilter.ORDER);
        });
        assertThat(output).contains("SpringTune limiter: SHADOW mode (algorithm=vegas, initial-limit=20, "
                + "min-limit=10, max-limit=200, warmup-samples=500)");
    }

    @Test
    void enforceModeIsLogged(CapturedOutput output) {
        servlet.withPropertyValues("springtune.limiter.enabled=true", "springtune.limiter.enforce=true",
                "springtune.limiter.algorithm=gradient2").run(context -> {
                    assertThat(context.getBean(AdaptiveLimiter.class).isEnforcing()).isTrue();
                });
        assertThat(output).contains("SpringTune limiter: ENFORCE mode (algorithm=gradient2");
    }

    @Test
    void reactiveApplicationGetsTheWebFilter() {
        reactive.withPropertyValues("springtune.limiter.enabled=true").run(context -> assertThat(context)
                .hasSingleBean(ConcurrencyLimitWebFilter.class).doesNotHaveBean(ConcurrencyLimitFilter.class));
    }

    @Test
    void reactiveWorksWithoutTheServletApi() {
        reactive.withClassLoader(new FilteredClassLoader("jakarta.servlet"))
                .withPropertyValues("springtune.limiter.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConcurrencyLimitWebFilter.class));
    }

    @Test
    void notInstalledOutsideWebApplications() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(LimiterAutoConfiguration.class))
                .withPropertyValues("springtune.limiter.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AdaptiveLimiter.class));
    }

    @Test
    void notInstalledWithoutNetflixCore() {
        servlet.withClassLoader(new FilteredClassLoader("com.netflix.concurrency.limits"))
                .withPropertyValues("springtune.limiter.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AdaptiveLimiter.class));
    }

    @Test
    void worksWithoutMicrometer() {
        servlet.withClassLoader(new FilteredClassLoader("io.micrometer"))
                .withPropertyValues("springtune.limiter.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ConcurrencyLimitFilter.class));
    }

    @Test
    void zeroMinLimitAndNegativeRetryAfterFailFast() {
        servlet.withPropertyValues("springtune.limiter.enabled=true", "springtune.limiter.min-limit=0")
                .run(context -> assertThat(context).hasFailed());
        servlet.withPropertyValues("springtune.limiter.enabled=true", "springtune.limiter.retry-after=-1s")
                .run(context -> assertThat(context).hasFailed());
        servlet.withPropertyValues("springtune.limiter.enabled=true", "springtune.limiter.initial-limit=5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void minAboveMaxFailsFast() {
        servlet.withPropertyValues("springtune.limiter.enabled=true", "springtune.limiter.min-limit=300")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("springtune.limiter.min-limit (300) must not be greater than "
                                    + "springtune.limiter.max-limit (200)");
                });
    }

    @Test
    void metrics() {
        servlet.withPropertyValues("springtune.limiter.enabled=true").run(context -> {
            MeterRegistry registry = new SimpleMeterRegistry();
            context.getBeansOfType(MeterBinder.class).values().forEach(binder -> binder.bindTo(registry));
            context.getBean(AdaptiveLimiter.class).bypass();
            assertThat(registry.get("springtune.limiter.limit").gauge().value()).isEqualTo(20.0);
            assertThat(registry.get("springtune.limiter.inflight").gauge().value()).isZero();
            assertThat(registry.get("springtune.limiter.requests").tag("outcome", "bypassed").functionCounter()
                    .count()).isEqualTo(1.0);
            assertThat(registry.get("springtune.limiter.requests").functionCounters()).extracting(
                    c -> c.getId().getTag("outcome"))
                    .containsExactlyInAnyOrder("accepted", "rejected", "shadow_rejected", "bypassed");
        });
    }

    @Test
    void userLimiterWins() {
        servlet.withPropertyValues("springtune.limiter.enabled=true")
                .withBean("custom", AdaptiveLimiter.class, () -> AdaptiveLimiter.create(customProperties()))
                .run(context -> assertThat(context.getBean(AdaptiveLimiter.class).limit()).isEqualTo(15));
    }

    private static LimiterProperties customProperties() {
        LimiterProperties properties = new LimiterProperties();
        properties.setInitialLimit(15);
        return properties;
    }
}
