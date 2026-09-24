package com.jmip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Registers the security headers and request limits as plain servlet filters.
 *
 * <p>The application has no user accounts, so a full security framework would add
 * configuration without protecting anything more. Both filters run just after Spring's
 * forwarded-header handling, so the rate limit sees the real client address behind nginx.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class WebSecurityConfig {

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestLimitFilter> requestLimitFilter(SecurityProperties properties,
                                                                         ObjectMapper objectMapper, Clock clock) {
        FilterRegistrationBean<RequestLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestLimitFilter(properties, objectMapper, clock));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
