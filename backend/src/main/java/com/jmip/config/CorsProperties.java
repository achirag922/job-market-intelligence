package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which browser origins may call this API.
 *
 * <p>Configured rather than hardcoded, because the frontend runs on a different origin in
 * every environment: the Vite dev server locally, something else entirely once deployed.
 *
 * @param allowedOrigins exact origins, e.g. {@code http://localhost:5173}. Deliberately
 *                       not a wildcard: a wildcard would let any site on the internet
 *                       read this API through a visitor's browser.
 */
@ConfigurationProperties(prefix = "jmip.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
