package com.jmip.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * V7.8: one summary line per request, and a request id tying together every log line it caused.
 *
 * <p>Logged: method, path (never the query string, which can carry search text), status and
 * duration. Never logged: headers, cookies, bodies. Server errors are WARN and slow requests
 * INFO; everything else is DEBUG, so a healthy production log is not one line per click.
 * Counts and timings per endpoint come from Actuator's {@code http.server.requests} metric.
 *
 * <p>The id is taken from an incoming {@code X-Request-Id} only when it looks like an id, so a
 * client cannot inject text into the logs through it, and is returned on the response for
 * support ("reference ...") and correlation with a proxy.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int MAX_PATH_LENGTH = 200;

    private final Duration slowThreshold;

    public RequestLoggingFilter(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestIdOf(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startedAt = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            // An exception escaping every handler becomes a 500 after this filter returns.
            int status = failed ? 500 : response.getStatus();
            log.atLevel(levelFor(status, durationMs))
                    .addKeyValue("http.method", request.getMethod())
                    .addKeyValue("url.path", pathOf(request))
                    .addKeyValue("http.status", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("method={} path={} status={} durationMs={}", request.getMethod(), pathOf(request), status, durationMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    Level levelFor(int status, long durationMs) {
        if (status >= 500) {
            return Level.WARN;
        }
        return durationMs >= slowThreshold.toMillis() ? Level.INFO : Level.DEBUG;
    }

    static String requestIdOf(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }

    /** Decoded, without query string or path parameters, control characters removed, bounded. */
    static String pathOf(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request).replaceAll("\\p{Cntrl}", "_");
        return path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) + "…" : path;
    }
}
