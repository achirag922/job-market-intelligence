package com.jmip.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V7.8: health groups, the metrics account, request ids, and what never reaches the log. */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.security.password.bcrypt-strength=4",
        "jmip.observability.metrics-password=metrics-test-password-0123",
        "logging.level.com.jmip.config.RequestLoggingFilter=DEBUG"})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String METRICS_PASSWORD = "metrics-test-password-0123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.execute("TRUNCATE users CASCADE");
    }

    @Test
    @DisplayName("health tells the application and the database apart, without signing in")
    void healthGroups() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/database")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("metrics need the metrics account; request, error, JVM and connection-pool metrics are there")
    void metrics() throws Exception {
        mockMvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").with(httpBasic("metrics", "wrong-password-0123456789")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").with(httpBasic("metrics", METRICS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItem("http.server.requests")))
                .andExpect(jsonPath("$.names", hasItem("jvm.memory.used")))
                .andExpect(jsonPath("$.names", hasItem("hikaricp.connections.active")))
                // V10.6: database pool waits and the latest ETL run.
                .andExpect(jsonPath("$.names", hasItem("hikaricp.connections.pending")))
                .andExpect(jsonPath("$.names", hasItem("jmip.etl.last.run.success")))
                .andExpect(jsonPath("$.names", hasItem("jmip.etl.last.run.age")));
        mockMvc.perform(get("/actuator/metrics/http.server.requests").param("tag", "outcome:CLIENT_ERROR")
                        .with(httpBasic("metrics", METRICS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"));
    }

    @Test
    @DisplayName("nothing beyond health, info and metrics is reachable, and the metrics account cannot use the API")
    void exposure() throws Exception {
        for (String path : new String[]{"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/threaddump"}) {
            int anonymous = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            int withAccount = mockMvc.perform(get(path).with(httpBasic("metrics", METRICS_PASSWORD))).andReturn().getResponse().getStatus();
            assertThat(anonymous).as(path).isIn(401, 403, 404);
            assertThat(withAccount).as(path).isIn(403, 404);
        }
        mockMvc.perform(get("/api/jobs").with(httpBasic("metrics", METRICS_PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("responses carry a request id")
    void requestIdHeader() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(header().exists("X-Request-Id"));
        mockMvc.perform(get("/actuator/health")).andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @DisplayName("signing up and in leaves no password, session id, CSRF token or metrics password in the log")
    void noSecretsInLogs(CapturedOutput output) throws Exception {
        String password = "violet otter lantern";
        String body = new ObjectMapper().writeValueAsString(
                Map.of("fullName", "Log Tester", "email", "log.tester@example.com", "password", password));
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE users SET email_verified_at = now()");
        MockHttpServletResponse login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String csrfToken = com.jayway.jsonpath.JsonPath.read(login.getContentAsString(), "$.csrfToken");
        String sessionId = login.getCookie("JMIP_SESSION") == null ? null : login.getCookie("JMIP_SESSION").getValue();

        assertThat(output).contains("method=POST path=/api/auth/login status=200");
        assertThat(output).doesNotContain(password).doesNotContain(csrfToken).doesNotContain(METRICS_PASSWORD);
        if (sessionId != null) {
            assertThat(output).doesNotContain(sessionId);
        }
    }
}
