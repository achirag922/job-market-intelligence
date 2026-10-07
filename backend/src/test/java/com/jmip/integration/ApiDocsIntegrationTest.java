package com.jmip.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V10.8: the OpenAPI description and Swagger UI, as served locally (the prod profile turns them off). */
@SpringBootTest(properties = {"jmip.ai.provider=stub"})
@AutoConfigureMockMvc
@Testcontainers
class ApiDocsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the OpenAPI document describes the API, its security, errors, tags and examples")
    void openApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("JMIP API"))
                .andExpect(jsonPath("$.components.securitySchemes.session.in").value("cookie"))
                .andExpect(jsonPath("$.components.securitySchemes.csrf.name").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.components.schemas", hasKey("ApiError")))
                .andExpect(jsonPath("$.paths", hasKey("/api/jobs")))
                .andExpect(jsonPath("$.paths", not(hasKey("/actuator/health"))))
                .andExpect(jsonPath("$.tags[*].name", hasItem("Authentication")))
                .andExpect(jsonPath("$.paths['/api/jobs'].get.tags[0]").value("Jobs"))
                .andExpect(jsonPath("$.paths['/api/jobs'].get.parameters[*].name", hasItem("skill")))
                .andExpect(jsonPath("$.paths['/api/jobs'].get.parameters[*].name", hasItem("page")))
                .andExpect(jsonPath("$.paths['/api/jobs'].get.responses", hasKey("401")))
                // Public routes need no session; login carries an example body.
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.requestBody.content['application/json'].examples", hasKey("example")))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses", hasKey("429")))
                .andExpect(jsonPath("$.paths['/api/admin/users'].get.responses['403'].description", containsString("ADMIN")));
    }

    @Test
    @DisplayName("each group has its own document")
    void groups() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths", hasKey("/api/admin/users")))
                .andExpect(jsonPath("$.paths", not(hasKey("/api/jobs"))));
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[*].name", hasItem("Jobs & applications")));
    }

    @Test
    @DisplayName("Swagger UI loads without a session, with a CSP that allows only its own scripts")
    void swaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
    }
}
