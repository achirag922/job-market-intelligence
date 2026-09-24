package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Request limits for the two endpoints that do expensive work: the AI assistant, which
 * costs a model call, and resume upload, which parses a PDF.
 *
 * @param rateLimitEnabled        off only in tests that exercise many requests
 * @param assistantPerMinute      assistant questions per client address per minute
 * @param uploadsPerMinute        resume uploads per client address per minute
 * @param assistantMaxBodyBytes   largest assistant, login or signup request body accepted
 * @param authPerMinute           login and signup attempts, together, per client address per minute
 */
@ConfigurationProperties(prefix = "jmip.security")
public record SecurityProperties(
        @DefaultValue("true") boolean rateLimitEnabled,
        @DefaultValue("20") int assistantPerMinute,
        @DefaultValue("10") int uploadsPerMinute,
        @DefaultValue("16384") int assistantMaxBodyBytes,
        @DefaultValue("10") int authPerMinute) {
}
