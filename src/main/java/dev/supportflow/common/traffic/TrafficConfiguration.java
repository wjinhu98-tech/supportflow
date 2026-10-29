package dev.supportflow.common.traffic;

import dev.supportflow.common.observability.RequestTraceFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrafficProperties.class)
public class TrafficConfiguration {
    @Bean TrafficQuotaService trafficQuotaService(TrafficProperties properties, MeterRegistry registry) {
        return new TrafficQuotaService(properties, registry, System::nanoTime);
    }

    @Bean AuthenticatedRequestLimitFilter authenticatedRequestLimitFilter(TrafficQuotaService quotas) {
        return new AuthenticatedRequestLimitFilter(quotas);
    }

    @Bean RequestTraceFilter requestTraceFilter(MeterRegistry registry) { return new RequestTraceFilter(registry); }

    // SecurityFilterChain owns ordering; servlet auto-registration would run these twice and before authentication.
    @Bean FilterRegistrationBean<AuthenticatedRequestLimitFilter> disableRequestLimitServletRegistration(AuthenticatedRequestLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean FilterRegistrationBean<RequestTraceFilter> disableTraceServletRegistration(RequestTraceFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
