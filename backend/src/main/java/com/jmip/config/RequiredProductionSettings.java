package com.jmip.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Stops a {@code prod} start before anything connects when a required setting is absent.
 *
 * <p>Spring leaves an unset {@code ${VAR}} in place as literal text, so without this check a
 * missing variable surfaces much later as a confusing error — a database host called
 * "${JMIP_DB_HOST}", or a resume folder of that name created in the working directory.
 * Registered in {@code META-INF/spring.factories}; does nothing outside {@code prod}.
 */
public class RequiredProductionSettings implements EnvironmentPostProcessor {

    static final List<String> REQUIRED = List.of(
            "JMIP_DB_HOST", "JMIP_DB_NAME", "JMIP_DB_USERNAME", "JMIP_DB_PASSWORD",
            "JMIP_RESUME_DIR", "JMIP_CORS_ALLOWED_ORIGINS");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> missing = REQUIRED.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            // Names only, never values.
            throw new IllegalStateException(
                    "The prod profile requires these environment variables: " + String.join(", ", missing));
        }
    }
}
