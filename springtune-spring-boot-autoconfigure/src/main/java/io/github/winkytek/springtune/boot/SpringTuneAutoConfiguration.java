package io.github.winkytek.springtune.boot;

import java.time.Clock;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * SpringTune runtime auto-configuration (observe/advise, and experimental apply). Disabled with
 * {@code springtune.enabled=false}. Components are discovered lazily, so ordering against
 * DataSource/TaskExecution auto-configuration is not required.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "springtune", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SpringTuneProperties.class)
public class SpringTuneAutoConfiguration {

    static final String[] SPRING_SECURITY_WEB = {
            "org.springframework.security.config.annotation.web.builders.HttpSecurity",
            "org.springframework.security.config.web.server.ServerHttpSecurity" };

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

    /** {@code springtune.mode=apply}: the change controller. Refuses to start without Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "springtune", name = "mode", havingValue = "apply")
    static class ApplyConfiguration {

        @Bean
        ChangeController springTuneChangeController(TuningService service, SpringTuneProperties properties,
                ApplicationEventPublisher publisher, ConfigurableListableBeanFactory beanFactory) {
            ClassLoader classLoader = beanFactory.getBeanClassLoader() != null ? beanFactory.getBeanClassLoader()
                    : SpringTuneAutoConfiguration.class.getClassLoader();
            boolean security = false;
            for (String type : SPRING_SECURITY_WEB) {
                security |= ClassUtils.isPresent(type, classLoader);
            }
            if (!security) {
                throw new IllegalStateException("springtune.mode=apply requires Spring Security on the classpath"
                        + " (spring-boot-starter-security), so that POST /actuator/tuning is authenticated and the"
                        + " caller recorded. Add it and secure the endpoint, or use springtune.mode=advise.");
            }
            ChangeController controller = new ChangeController(properties, service.clock(), service::writableKnobs,
                    service::evaluate, service.budgetCap(),
                    entry -> publisher.publishEvent(new SpringTuneChangeEvent(service, entry)));
            service.attach(controller);
            return controller;
        }
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

        /** Web-only write operation; exists only in apply mode. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "springtune", name = "mode", havingValue = "apply")
        @ConditionalOnAvailableEndpoint(endpoint = TuningEndpoint.class)
        TuningApplyWebExtension springTuneApplyWebExtension(ChangeController controller,
                SpringTuneProperties properties) {
            return new TuningApplyWebExtension(controller, properties.getApply().getRequiredRole());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class MetricsConfiguration {

        @Bean
        io.micrometer.core.instrument.binder.MeterBinder springTuneMetrics(TuningService service) {
            return MicrometerSupport.metrics(service);
        }

        @Bean
        @ConditionalOnProperty(prefix = "springtune", name = "mode", havingValue = "apply")
        MicrometerSupport.ChangeMetrics springTuneChangeMetrics() {
            return new MicrometerSupport.ChangeMetrics();
        }
    }
}
