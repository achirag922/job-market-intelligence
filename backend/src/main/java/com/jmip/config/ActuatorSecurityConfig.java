package com.jmip.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * V7.8: who may read Actuator. Health and info stay public (status only in production), as
 * before. Metrics need the one metrics account over HTTP Basic, and are closed to everyone
 * when no password is configured. Only health, info and metrics are exposed at all; env,
 * configprops, beans, heapdump and the rest do not exist over HTTP.
 *
 * <p>A separate chain for /actuator/** only, ahead of the application's: sign-in, sessions,
 * CSRF and CORS for the API are untouched. The metrics account lives in this chain alone and
 * cannot sign in to the application.
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ActuatorSecurityConfig {

    static final String METRICS_ROLE = "METRICS";

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http, ObservabilityProperties properties)
            throws Exception {
        http.securityMatcher("/actuator/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    if (properties.metricsEnabled()) {
                        requests.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**")
                                .hasRole(METRICS_ROLE);
                    }
                    requests.anyRequest().denyAll();
                });
        if (properties.metricsEnabled()) {
            http.httpBasic(Customizer.withDefaults()).authenticationManager(metricsAuthentication(properties));
        }
        return http.build();
    }

    /** The request summary and request id run first, so they cover every other filter's outcome. */
    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter(ObservabilityProperties properties) {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter(properties.slowRequestThreshold()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /** Built here, not as a bean, so it cannot replace the application's own user lookup. */
    private static ProviderManager metricsAuthentication(ObservabilityProperties properties) {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(User.withUsername(properties.metricsUsername())
                .password(encoder.encode(properties.metricsPassword()))
                .roles(METRICS_ROLE)
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
