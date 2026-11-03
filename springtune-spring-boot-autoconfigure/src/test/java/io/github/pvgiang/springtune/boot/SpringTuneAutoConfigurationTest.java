package io.github.pvgiang.springtune.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SpringTuneAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpringTuneAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class Infrastructure {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:autoconfig;DB_CLOSE_DELAY=-1");
            dataSource.setPoolName("orders");
            dataSource.setMaximumPoolSize(7);
            return dataSource;
        }

        @Bean
        ThreadPoolTaskExecutor applicationTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(3);
            executor.setQueueCapacity(50);
            return executor;
        }
    }

    @Test
    void registersServiceSamplerAndMetricsByDefault() {
        runner.withUserConfiguration(Infrastructure.class).run(context -> {
            assertThat(context).hasSingleBean(TuningService.class).hasSingleBean(TuningSampler.class)
                    .hasSingleBean(MeterBinder.class);
            TuningService service = context.getBean(TuningService.class);
            assertThat(service.components()).extracting(TunedComponent::id)
                    .containsExactly("executor:applicationTaskExecutor", "hikari:orders");
            assertThat(service.snapshot().mode()).isEqualTo("ADVISE");
            assertThat(service.snapshot().applySupported()).isFalse();
        });
    }

    @Test
    void knobsReflectLiveValues() {
        runner.withUserConfiguration(Infrastructure.class).run(context -> {
            TuningSnapshot snapshot = context.getBean(TuningService.class).snapshot();
            TuningSnapshot.Component hikari = snapshot.components().stream()
                    .filter(c -> c.id().equals("hikari:orders")).findFirst().orElseThrow();
            assertThat(hikari.knobs()).anySatisfy(k -> {
                assertThat(k.id()).isEqualTo("hikari.orders.maximumPoolSize");
                assertThat(k.currentValue()).isEqualTo(7);
            });
            TuningSnapshot.Component executor = snapshot.components().stream()
                    .filter(c -> c.id().startsWith("executor:")).findFirst().orElseThrow();
            assertThat(executor.knobs()).anySatisfy(k -> {
                assertThat(k.name()).isEqualTo("queueCapacity");
                assertThat(k.currentValue()).isEqualTo(50);
            });
        });
    }

    @Test
    void endpointRequiresExposure() {
        runner.withUserConfiguration(Infrastructure.class)
                .run(context -> assertThat(context).doesNotHaveBean(TuningEndpoint.class));
        runner.withUserConfiguration(Infrastructure.class)
                .withPropertyValues("management.endpoints.web.exposure.include=tuning")
                .run(context -> assertThat(context).hasSingleBean(TuningEndpoint.class));
    }

    @Test
    void disabledRemovesEverything() {
        runner.withPropertyValues("springtune.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(TuningService.class).doesNotHaveBean(TuningSampler.class)
                .doesNotHaveBean(TuningEndpoint.class));
    }

    @Test
    void applyModeIsRejectedAtStartup() {
        runner.withPropertyValues("springtune.mode=apply").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("Mode.apply");
            assertThat(context.getStartupFailure()).hasStackTraceContaining("springtune.mode");
        });
    }

    @Test
    void halfConfiguredBudgetIsRejectedAtStartup() {
        runner.withPropertyValues("springtune.budget.database-max-connections=100").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("must be set together");
        });
    }

    @Test
    void budgetCapUsesSharedFormula() {
        runner.withUserConfiguration(Infrastructure.class)
                .withPropertyValues("springtune.budget.database-max-connections=100", "springtune.budget.max-replicas=6")
                .run(context -> assertThat(context.getBean(TuningService.class).snapshot().budget().perInstanceCap())
                        .isEqualTo(13));
    }

    @Test
    void worksWithoutHikariOnTheClasspath() {
        runner.withClassLoader(new FilteredClassLoader(HikariDataSource.class)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TuningService.class);
            assertThat(context.getBean(TuningService.class).components()).isEmpty();
        });
    }

    @Test
    void observeModeProducesNoRecommendations() {
        runner.withUserConfiguration(Infrastructure.class).withPropertyValues("springtune.mode=observe")
                .run(context -> {
                    TuningService service = context.getBean(TuningService.class);
                    service.sample();
                    assertThat(service.recommendations()).isEmpty();
                    assertThat(service.snapshot().mode()).isEqualTo("OBSERVE");
                });
    }
}
