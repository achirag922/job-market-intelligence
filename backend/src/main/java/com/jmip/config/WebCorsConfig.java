package com.jmip.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the browser frontend to call the API cross-origin.
 *
 * <p>Scoped to {@code /api/**} and to read-only methods, which is all this API offers.
 * Credentials are not allowed: there is no authentication yet, so no cookie or auth
 * header should ever be sent, and permitting them would mean giving up exact-origin
 * matching later.
 */
@Configuration
// Declared here as well as scanned application-wide, so that @WebMvcTest slices — which
// load this configurer but do not scan configuration properties — still get the bean.
@EnableConfigurationProperties(CorsProperties.class)
public class WebCorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebCorsConfig.class);

    private final CorsProperties corsProperties;

    public WebCorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins().isEmpty()) {
            log.warn("No CORS origins configured; browser clients on another origin will be refused");
            return;
        }
        log.info("CORS enabled for origins {}", corsProperties.allowedOrigins());
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                // POST is needed for resume upload, which is the only endpoint that
                // writes. Everything else remains read-only.
                .allowedMethods("GET", "POST", "OPTIONS")
                // Only what the frontend sends, including the CSRF token header.
                .allowedHeaders("Content-Type", "Accept", "X-CSRF-TOKEN")
                // The session cookie must travel when the frontend runs on another origin (the Vite
                // dev server). Safe only because the origins are exact: wildcards are refused.
                .allowCredentials(true)
                .maxAge(3600);
    }
}
