package com.jmip.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V10.5: the public/private boundary of the API and what errors reveal. Public: sign-up and sign-in,
 * the session check, published profiles and health. Everything else needs a signed-in user, the admin
 * API needs an admin, and error bodies never carry exception or framework details.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.security.rate-limit-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class EndpointAccessIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final List<String> PRIVATE = List.of(
            "/api/jobs", "/api/resumes", "/api/applications", "/api/dashboard/analytics", "/api/career-progress",
            "/api/career-goals", "/api/notifications", "/api/onboarding", "/api/learning", "/api/portfolio",
            "/api/interviews", "/api/job-alerts", "/api/match-preferences", "/api/market/intelligence",
            "/api/skills", "/api/companies", "/api/etl/runs", "/api/admin/overview", "/api/admin/users");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("every private endpoint answers 401 to an anonymous caller")
    void privateEndpointsNeedSignIn() throws Exception {
        for (String path : PRIVATE) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        // No metrics account is configured here, so the metrics endpoint is closed to everyone (deny-all).
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the admin API is refused to a signed-in non-admin")
    void adminNeedsAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/overview").with(user("member@example.com").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").with(user("member@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("public endpoints are reachable without a session")
    void publicEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // Unpublished or unknown profiles are 404, not 401: the route itself is public.
        mockMvc.perform(get("/api/public/profiles/no-such-profile")).andExpect(status().isNotFound());
        // The session check is public and simply reports "not signed in".
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("internal actuator endpoints are not exposed, even to a signed-in user")
    void actuatorInternalsHidden() throws Exception {
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump")) {
            // The actuator chain ends in deny-all: refused without revealing whether the endpoint exists.
            mockMvc.perform(get(path).with(user("member@example.com").roles("USER"))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("error bodies carry a plain message, never exception classes, stack traces or framework names")
    void errorsRevealNothing() throws Exception {
        String malformed = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(get("/api/no-such-endpoint").with(user("member@example.com").roles("USER")))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        for (String body : List.of(malformed, unknown)) {
            assertThat(body).doesNotContain("Exception", "at com.", "at org.", "jackson", "springframework", "trace");
        }
    }
}
