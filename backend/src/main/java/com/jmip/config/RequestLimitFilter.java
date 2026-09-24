package com.jmip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmip.common.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic abuse protection for the assistant and resume upload, without a new library.
 *
 * <p>A fixed one-minute window per client address and endpoint. It is deliberately simple:
 * it lives in one process's memory, so it protects a single instance from a runaway client
 * or a script, not a distributed attack. Behind nginx the client address comes from
 * X-Forwarded-For, which nginx overwrites rather than appends to, so it cannot be spoofed
 * through the proxy.
 *
 * <p>The assistant body is also capped by declared length before anything reads it: the
 * question itself is limited by validation, and nothing legitimate needs a large body.
 */
public class RequestLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLimitFilter.class);

    static final String ASSISTANT_PATH = "/api/assistant/query";
    static final String UPLOAD_PATH = "/api/resumes";
    /** Sign-in and sign-up share one allowance, so neither can be used to guess passwords or probe emails. */
    static final String AUTH_ENDPOINT = "/api/auth";
    static final String LOGIN_PATH = "/api/auth/login";
    static final String SIGNUP_PATH = "/api/auth/signup";
    private static final long WINDOW_MILLIS = 60_000;
    /** Expired windows are swept once the table grows past this, so it cannot grow unbounded. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestLimitFilter(SecurityProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || endpointOf(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = endpointOf(request);

        if (ASSISTANT_PATH.equals(endpoint) || AUTH_ENDPOINT.equals(endpoint)) {
            long length = request.getContentLengthLong();
            if (length < 0) {
                reject(response, request, HttpStatus.LENGTH_REQUIRED, "A Content-Length header is required");
                return;
            }
            if (length > properties.assistantMaxBodyBytes()) {
                reject(response, request, HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large");
                return;
            }
        }

        if (properties.rateLimitEnabled()) {
            int limit = switch (endpoint) {
                case ASSISTANT_PATH -> properties.assistantPerMinute();
                case AUTH_ENDPOINT -> properties.authPerMinute();
                default -> properties.uploadsPerMinute();
            };
            long now = clock.millis();
            Window window = windows.compute(endpoint + '|' + request.getRemoteAddr(),
                    (key, current) -> current == null || now - current.start >= WINDOW_MILLIS
                            ? new Window(now) : current);
            if (window.count.incrementAndGet() > limit) {
                long retryAfterSeconds = Math.max(1, (window.start + WINDOW_MILLIS - now + 999) / 1000);
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                // The address is not logged: it is personal data and adds nothing to the count.
                log.warn("Rate limit reached on {}", endpoint);
                reject(response, request, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait and try again");
                return;
            }
            if (windows.size() > SWEEP_THRESHOLD) {
                windows.values().removeIf(stale -> now - stale.start >= WINDOW_MILLIS);
            }
        }

        chain.doFilter(request, response);
    }

    private static String endpointOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals(ASSISTANT_PATH)) {
            return ASSISTANT_PATH;
        }
        if (path.equals(LOGIN_PATH) || path.equals(SIGNUP_PATH)) {
            return AUTH_ENDPOINT;
        }
        if (path.equals(UPLOAD_PATH) || path.equals(UPLOAD_PATH + "/")) {
            return UPLOAD_PATH;
        }
        return null;
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }

    private static final class Window {
        private final long start;
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        private Window(long start) {
            this.start = start;
        }
    }
}
