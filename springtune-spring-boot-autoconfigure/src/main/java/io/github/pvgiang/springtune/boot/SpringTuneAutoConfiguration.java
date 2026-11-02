package io.github.pvgiang.springtune.boot;

import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringTune runtime auto-configuration (observe/advise). Disabled with {@code springtune.enabled=false}.
 * Components are discovered lazily, so ordering against DataSource/TaskExecution auto-configuration is not
 * required.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "springtune", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SpringTuneProperties.class)
public class SpringTuneAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TuningService springTuneTuningService(SpringTuneProperties properties, ListableBeanFactory beanFactory) {
        return new TuningService(properties, beanFactory, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    public TuningSampler springTuneSampler(TuningService service, SpringTuneProperties properties) {
        return new TuningSampler(service, properties.getSampling().getInterval());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class EndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint(endpoint = TuningEndpoint.class)
        TuningEndpoint springTuneEndpoint(TuningService service) {
            return new TuningEndpoint(service);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class MetricsConfiguration {

        @Bean
        io.micrometer.core.instrument.binder.MeterBinder springTuneMetrics(TuningService service) {
            return MicrometerSupport.metrics(service);
        }
    }
}
