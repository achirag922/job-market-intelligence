package com.jmip.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6.10.8 review regressions, against a real server so paths reach Tomcat exactly as sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jmip.ai.provider=stub", "jmip.security.rate-limit-enabled=true", "jmip.security.auth-per-minute=2"})
@Testcontainers
class SecurityReviewIntegrationTest {

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

    @Test
    @DisplayName("percent-encoding the path does not escape the login rate limit")
    void encodedPathIsRateLimited() throws Exception {
        String credentials = "{\"email\":\"nobody@example.com\",\"password\":\"wrong password here\"}";
        List<Integer> statuses = new ArrayList<>();
        // Each spelling reaches the login endpoint; all count against the same allowance.
        for (String path : List.of("/api/auth/login", "/api/auth/log%69n", "/api/auth/%6Cogin", "/api/auth/login;x=1")) {
            statuses.add(post(path, credentials));
        }

        assertThat(statuses.subList(0, 2)).containsOnly(401);
        assertThat(statuses.subList(2, 4)).containsOnly(429);
    }

    private int post(String path, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }
}
