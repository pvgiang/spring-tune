package io.github.winky.springtune.limiter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Adaptive concurrency limiter. Installed only with {@code springtune.limiter.enabled=true}; then in shadow mode
 * (nothing is rejected) until {@code springtune.limiter.enforce=true}. Servlet applications get
 * {@link ConcurrencyLimitFilter}, reactive ones {@link ConcurrencyLimitWebFilter}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.netflix.concurrency.limits.limiter.SimpleLimiter")
@ConditionalOnProperty(prefix = "springtune.limiter", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication
@EnableConfigurationProperties(LimiterProperties.class)
public class LimiterAutoConfiguration {

    private static final Log logger = LogFactory.getLog(LimiterAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AdaptiveLimiter springTuneAdaptiveLimiter(LimiterProperties properties) {
        AdaptiveLimiter limiter = AdaptiveLimiter.create(properties);
        String settings = "algorithm=" + properties.getAlgorithm().name().toLowerCase(java.util.Locale.ROOT)
                + ", initial-limit=" + properties.getInitialLimit() + ", min-limit=" + properties.getMinLimit()
                + ", max-limit=" + properties.getMaxLimit() + ", warmup-samples=" + properties.getWarmupSamples();
        if (properties.isEnforce()) {
            logger.info("SpringTune limiter: ENFORCE mode (" + settings + "). After warmup, requests over the "
                    + "adaptive limit are rejected with " + properties.getRejectStatus() + " and Retry-After.");
        } else {
            logger.info("SpringTune limiter: SHADOW mode (" + settings + "). Nothing is rejected; would-be "
                    + "rejections are counted as springtune.limiter.requests{outcome=shadow_rejected}. "
                    + "Set springtune.limiter.enforce=true to reject.");
        }
        return limiter;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.filter.OncePerRequestFilter"})
    static class ServletConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ConcurrencyLimitFilter springTuneConcurrencyLimitFilter(AdaptiveLimiter limiter,
                LimiterProperties properties, Environment environment) {
            return new ConcurrencyLimitFilter(limiter, new RequestPolicy(properties, environment), properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    static class ReactiveConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ConcurrencyLimitWebFilter springTuneConcurrencyLimitWebFilter(AdaptiveLimiter limiter,
                LimiterProperties properties, Environment environment) {
            return new ConcurrencyLimitWebFilter(limiter, new RequestPolicy(properties, environment), properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class MetricsConfiguration {

        @Bean
        io.micrometer.core.instrument.binder.MeterBinder springTuneLimiterMetrics(AdaptiveLimiter limiter) {
            return new LimiterMetrics(limiter);
        }
    }
}
