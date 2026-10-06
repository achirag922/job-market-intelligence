package com.jmip.etl.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Stops a {@code prod} run before it connects when a database setting is absent, instead of
 * failing later on a host literally named "${JMIP_DB_HOST}". Registered in
 * {@code META-INF/spring.factories}; does nothing outside {@code prod}.
 */
public class RequiredProductionSettings implements EnvironmentPostProcessor {

    static final List<String> REQUIRED = List.of(
            "JMIP_DB_HOST", "JMIP_DB_NAME", "JMIP_DB_USERNAME", "JMIP_DB_PASSWORD");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> missing = REQUIRED.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The prod profile requires these environment variables: " + String.join(", ", missing));
        }
    }
}
