package com.jmip.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLimitFilterTest {

    private final MutableClock clock = new MutableClock();

    private RequestLimitFilter filter(boolean enabled, int assistantPerMinute, int uploadsPerMinute) {
        return new RequestLimitFilter(new SecurityProperties(enabled, assistantPerMinute, uploadsPerMinute, 100, 3),
                new ObjectMapper().findAndRegisterModules(), clock);
    }

    @Test
    @DisplayName("allows the configured number of assistant questions per client, then 429 with Retry-After")
    void assistantRateLimit() throws Exception {
        RequestLimitFilter filter = filter(true, 2, 10);

        assertThat(send(filter, assistant("10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(send(filter, assistant("10.0.0.1")).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = send(filter, assistant("10.0.0.1"));

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Too many requests").doesNotContain("10.0.0.1");
        // Another client has its own allowance.
        assertThat(send(filter, assistant("10.0.0.2")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the window resets after a minute")
    void windowResets() throws Exception {
        RequestLimitFilter filter = filter(true, 1, 10);
        send(filter, assistant("10.0.0.1"));
        assertThat(send(filter, assistant("10.0.0.1")).getStatus()).isEqualTo(429);

        clock.advanceMillis(60_000);

        assertThat(send(filter, assistant("10.0.0.1")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("uploads have their own limit, separate from the assistant")
    void uploadRateLimit() throws Exception {
        RequestLimitFilter filter = filter(true, 1, 2);

        assertThat(send(filter, upload()).getStatus()).isEqualTo(200);
        assertThat(send(filter, upload()).getStatus()).isEqualTo(200);
        assertThat(send(filter, upload()).getStatus()).isEqualTo(429);
        assertThat(send(filter, assistant("127.0.0.1")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("an oversized or length-less assistant body is refused before it is read")
    void assistantBodyLimits() throws Exception {
        RequestLimitFilter filter = filter(false, 1, 1);

        MockHttpServletRequest large = assistant("10.0.0.1");
        large.setContent(new byte[101]);
        MockHttpServletResponse tooLarge = send(filter, large);
        assertThat(tooLarge.getStatus()).isEqualTo(413);
        assertThat(tooLarge.getContentAsString()).contains("\"status\":413");

        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", RequestLimitFilter.ASSISTANT_PATH);
        assertThat(send(filter, chunked).getStatus()).isEqualTo(411);
    }

    @Test
    @DisplayName("reads and other endpoints are never limited, and disabled means disabled")
    void scope() throws Exception {
        RequestLimitFilter filter = filter(true, 1, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, new MockHttpServletRequest("GET", "/api/resumes/abc")).getStatus()).isEqualTo(200);
            assertThat(send(filter, new MockHttpServletRequest("GET", "/api/jobs")).getStatus()).isEqualTo(200);
        }

        RequestLimitFilter disabled = filter(false, 1, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(send(disabled, assistant("10.0.0.1")).getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("CORS origins are exact: a wildcard is refused at startup, blanks are dropped")
    void corsOrigins() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CorsProperties(List.of(" http://localhost:3000 ", "")).allowedOrigins())
                .containsExactly("http://localhost:3000");
    }

    @Test
    @DisplayName("the AI settings never print the API key")
    void aiKeyNeverPrinted() {
        AiProperties properties = new AiProperties("anthropic", "sk-test-do-not-print", "model", null, 10, null);

        assertThat(properties.toString()).doesNotContain("sk-test-do-not-print").contains("apiKey=****");
    }

    private static MockHttpServletRequest assistant(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", RequestLimitFilter.ASSISTANT_PATH);
        request.setRemoteAddr(remoteAddress);
        request.setContent("{\"question\":\"q\"}".getBytes());
        return request;
    }

    private static MockHttpServletRequest upload() {
        return new MockHttpServletRequest("POST", RequestLimitFilter.UPLOAD_PATH);
    }

    private static MockHttpServletResponse send(RequestLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T10:00:00Z");

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
