package com.jmip.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V6.10.6: the real prod profile, as deployed behind an HTTPS reverse proxy. Deployment
 * values come in as environment-style properties, the way Compose supplies them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "JMIP_DB_HOST=unused", "JMIP_DB_NAME=unused", "JMIP_DB_USERNAME=unused", "JMIP_DB_PASSWORD=unused",
        "JMIP_RESUME_DIR=target/prod-profile-resumes", "JMIP_OTP_SECRET=prod-profile-test",
        "JMIP_RESUME_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        // Real delivery, as a public deployment must use; nothing listens on port 9, so each
        // send fails fast and signup carries on, as it does when the mail server is down.
        "JMIP_VERIFICATION_DELIVERY=smtp", "JMIP_MAIL_USERNAME=sender@example.com", "JMIP_MAIL_PASSWORD=unused",
        "JMIP_MAIL_HOST=127.0.0.1", "JMIP_MAIL_PORT=9",
        "JMIP_CORS_ALLOWED_ORIGINS=https://jmip.example.com",
        "jmip.ai.provider=stub", "jmip.security.password.bcrypt-strength=4"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Testcontainers
class ProductionProfileIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    /** NIO2 works in every environment this runs in, including restricted sandboxes. */
    @TestConfiguration
    static class Nio2Connector {
        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2() {
            return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE users CASCADE");
    }

    @Test
    @DisplayName("the login cookie is HttpOnly, Secure and SameSite=Strict in production")
    void secureSessionCookie() throws Exception {
        String credentials = new ObjectMapper().writeValueAsString(
                Map.of("fullName", "Prod Tester", "email", "prod.tester@example.com", "password", "violet otter lantern"));
        assertThat(post("/api/auth/signup", credentials).status()).isEqualTo(201);
        jdbcTemplate.update("UPDATE users SET email_verified_at = now()");

        Response login = post("/api/auth/login", credentials);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("JMIP_SESSION=").contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/"));
    }

    @Test
    @DisplayName("HSTS is sent when the proxy says the browser used HTTPS, and never over plain HTTP")
    void hstsFollowsForwardedProto() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https").header("Host", "jmip.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")));
        mockMvc.perform(get("/api/jobs").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("Strict-Transport-Security"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("health reports status only, no component details, in production")
    void healthHidesDetails() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    @DisplayName("CORS admits the configured HTTPS origin with credentials, and nothing else")
    void corsAllowsOnlyTheConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/jobs").header("Origin", "https://jmip.example.com")
                        .header("Access-Control-Request-Method", "GET").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://jmip.example.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        for (String origin : List.of("http://jmip.example.com", "https://evil.example", "http://localhost:5173")) {
            mockMvc.perform(options("/api/jobs").header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    // ------------------------------------------------------------------ plain HTTP helper

    private record Response(int status, List<String> setCookies) {
    }

    /** Blocking HttpURLConnection: the NIO-based java.net.http client fails in some sandboxes. */
    private Response post(String path, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        List<String> cookies = connection.getHeaderFields().getOrDefault("Set-Cookie", List.of());
        connection.disconnect();
        return new Response(status, cookies);
    }
}
