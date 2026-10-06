package com.jmip.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingFilterTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("each request gets an id, returned in X-Request-Id and present in the MDC while it runs")
    void requestId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestLoggingFilter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/jobs"), response,
                (req, res) -> seen.set(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)));

        assertThat(response.getHeader("X-Request-Id")).isNotBlank().isEqualTo(seen.get());
        assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    @DisplayName("an incoming request id is reused only when it looks like an id")
    void incomingRequestId() throws Exception {
        MockHttpServletRequest good = request("GET", "/api/jobs");
        good.addHeader("X-Request-Id", "proxy-1234-abcd");
        MockHttpServletResponse goodResponse = new MockHttpServletResponse();
        new RequestLoggingFilter(Duration.ofSeconds(1)).doFilter(good, goodResponse, new MockFilterChain());
        assertThat(goodResponse.getHeader("X-Request-Id")).isEqualTo("proxy-1234-abcd");

        MockHttpServletRequest forged = request("GET", "/api/jobs");
        forged.addHeader("X-Request-Id", "x\nFAKE LOG LINE status=200");
        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
        new RequestLoggingFilter(Duration.ofSeconds(1)).doFilter(forged, forgedResponse, new MockFilterChain());
        assertThat(forgedResponse.getHeader("X-Request-Id")).doesNotContain("FAKE").hasSize(36);
    }

    @Test
    @DisplayName("the summary has method, path, status and duration, and never the query, cookies or credentials")
    void summaryContent() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/login");
        request.setQueryString("email=jane@example.com");
        request.addHeader("Authorization", "Bearer secret-token-value");
        request.addHeader("Cookie", "JMIP_SESSION=session-cookie-value");
        request.setContent("{\"password\":\"hunter2-password\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestLoggingFilter(Duration.ofSeconds(1)).doFilter(request, response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(503));

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).startsWith("method=POST path=/api/auth/login status=503 durationMs=");
        String everything = event.getFormattedMessage() + event.getKeyValuePairs();
        assertThat(everything).doesNotContain("jane@example.com", "secret-token-value", "session-cookie-value", "hunter2");
    }

    @Test
    @DisplayName("healthy fast requests are DEBUG, slow ones INFO, server errors WARN")
    void levels() {
        RequestLoggingFilter filter = new RequestLoggingFilter(Duration.ofMillis(500));
        assertThat(filter.levelFor(200, 20)).isEqualTo(org.slf4j.event.Level.DEBUG);
        assertThat(filter.levelFor(404, 20)).isEqualTo(org.slf4j.event.Level.DEBUG);
        assertThat(filter.levelFor(200, 900)).isEqualTo(org.slf4j.event.Level.INFO);
        assertThat(filter.levelFor(500, 5)).isEqualTo(org.slf4j.event.Level.WARN);
    }

    @Test
    @DisplayName("an exception escaping the chain is logged as a 500 and still propagates, and the MDC is cleared")
    void escapingException() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestLoggingFilter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/jobs"), response,
                (req, res) -> {
                    throw new ServletException("boom");
                })).isInstanceOf(ServletException.class);

        assertThat(appender.list.get(0).getFormattedMessage()).contains("status=500");
        assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    @DisplayName("control characters in the path cannot forge log lines")
    void pathSanitised() {
        MockHttpServletRequest request = request("GET", "/api/jobs%0AFAKE");
        request.setRequestURI("/api/jobs\nFAKE");
        assertThat(RequestLoggingFilter.pathOf(request)).doesNotContain("\n");
    }
}
