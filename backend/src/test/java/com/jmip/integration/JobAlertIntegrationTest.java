package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class JobAlertIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String JAVA_ALERT = """
            {"name":"  Java in Berlin ","keywords":"backend","category":"Software Engineering",
             "location":"Berlin","experience":"2-5","skill":"Java","frequency":"DAILY"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE users CASCADE");
        aliceId = account(ALICE);
        bobId = account(BOB);
    }

    @Test
    @DisplayName("creating an alert stores it for the signed-in account, trimmed, active, with timestamps")
    void create() throws Exception {
        // A userId in the body is not a field of the request and changes nothing.
        String body = JAVA_ALERT.replace("\"frequency\"", "\"userId\":\"" + bobId + "\",\"frequency\"");
        mockMvc.perform(json(post("/api/job-alerts"), body).with(as(ALICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Java in Berlin"))
                .andExpect(jsonPath("$.experience").value("2-5"))
                .andExpect(jsonPath("$.frequency").value("DAILY"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.userId").doesNotExist());

        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM job_alerts", UUID.class)).containsExactly(aliceId);
    }

    @Test
    @DisplayName("the list holds only the caller's own alerts, newest first")
    void listOwnAlerts() throws Exception {
        create(ALICE, JAVA_ALERT);
        String second = create(ALICE, "{\"name\":\"Remote data\",\"keywords\":\"data\",\"frequency\":\"WEEKLY\"}");
        create(BOB, "{\"name\":\"Bob's alert\",\"skill\":\"Go\",\"frequency\":\"DAILY\"}");

        mockMvc.perform(get("/api/job-alerts").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].frequency").value("WEEKLY"))
                .andExpect(jsonPath("$[1].name").value("Java in Berlin"));
        mockMvc.perform(get("/api/job-alerts").with(as(BOB))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("the owner can read and replace an alert; created time and status are kept")
    void updateOwnAlert() throws Exception {
        String id = create(ALICE, JAVA_ALERT);
        String createdAt = JsonPath.read(body(get("/api/job-alerts/" + id), ALICE), "$.createdAt");

        mockMvc.perform(json(put("/api/job-alerts/" + id),
                        "{\"name\":\"Senior Java\",\"skill\":\"Java\",\"experience\":\"8+\",\"frequency\":\"WEEKLY\"}")
                        .with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Senior Java"))
                .andExpect(jsonPath("$.keywords").doesNotExist())
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.experience").value("8+"))
                .andExpect(jsonPath("$.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").value(createdAt));
    }

    @Test
    @DisplayName("an alert can be paused and resumed")
    void activateAndDeactivate() throws Exception {
        String id = create(ALICE, JAVA_ALERT);

        mockMvc.perform(json(patch("/api/job-alerts/" + id + "/status"), "{\"active\":false}").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Java in Berlin"));
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM job_alerts", Boolean.class)).isFalse();

        mockMvc.perform(json(patch("/api/job-alerts/" + id + "/status"), "{\"active\":true}").with(as(ALICE)))
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(json(patch("/api/job-alerts/" + id + "/status"), "{}").with(as(ALICE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the owner can delete an alert")
    void deleteOwnAlert() throws Exception {
        String id = create(ALICE, JAVA_ALERT);

        mockMvc.perform(delete("/api/job-alerts/" + id).with(as(ALICE))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/job-alerts/" + id).with(as(ALICE))).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_alerts", Long.class)).isZero();
    }

    @Test
    @DisplayName("another account's alert is a 404 for every operation, and is left untouched")
    void cannotTouchAnotherAccountsAlert() throws Exception {
        String id = create(ALICE, JAVA_ALERT);
        String path = "/api/job-alerts/" + id;

        mockMvc.perform(get(path).with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(json(put(path), "{\"name\":\"Taken\",\"skill\":\"Go\",\"frequency\":\"WEEKLY\"}").with(as(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(patch(path + "/status"), "{\"active\":false}").with(as(BOB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(path).with(as(BOB))).andExpect(status().isNotFound());

        mockMvc.perform(get(path).with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Java in Berlin"))
                .andExpect(jsonPath("$.active").value(true));
        // A missing id answers the same, so Bob cannot tell the two apart.
        mockMvc.perform(get("/api/job-alerts/" + UUID.randomUUID()).with(as(BOB))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("job alerts need a signed-in user")
    void requiresSignIn() throws Exception {
        String id = create(ALICE, JAVA_ALERT);

        mockMvc.perform(get("/api/job-alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/job-alerts/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/job-alerts"), JAVA_ALERT)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/job-alerts/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("invalid alerts are refused with 400 and nothing is saved")
    void validation() throws Exception {
        List<String> invalid = List.of(
                "{\"name\":\"   \",\"skill\":\"Java\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"" + "x".repeat(101) + "\",\"skill\":\"Java\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"Anything\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"Blank criteria\",\"keywords\":\" \",\"location\":\"\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"Bad level\",\"experience\":\"10-20\",\"frequency\":\"DAILY\"}",
                "{\"name\":\"No frequency\",\"skill\":\"Java\"}",
                "{\"name\":\"Hourly\",\"skill\":\"Java\",\"frequency\":\"HOURLY\"}",
                "{\"name\":\"Long skill\",\"skill\":\"" + "s".repeat(101) + "\",\"frequency\":\"DAILY\"}");
        for (String body : invalid) {
            mockMvc.perform(json(post("/api/job-alerts"), body).with(as(ALICE))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(json(post("/api/job-alerts"), "{\"name\":\"Anything\",\"frequency\":\"DAILY\"}").with(as(ALICE)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("criteriaGiven"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(containsString("at least one of")));
        mockMvc.perform(get("/api/job-alerts/not-a-uuid").with(as(ALICE))).andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_alerts", Long.class)).isZero();
    }

    @Test
    @DisplayName("an account can keep at most 25 alerts")
    void perAccountLimit() throws Exception {
        for (int i = 0; i < 25; i++) {
            create(ALICE, "{\"name\":\"Alert " + i + "\",\"skill\":\"Java\",\"frequency\":\"DAILY\"}");
        }

        mockMvc.perform(json(post("/api/job-alerts"), JAVA_ALERT).with(as(ALICE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at most 25")));
        // Bob's allowance is his own.
        create(BOB, JAVA_ALERT);
    }

    @Test
    @DisplayName("the database enforces frequency, experience and criteria, and drops alerts with their account")
    void databaseConstraints() throws Exception {
        String insert = "INSERT INTO job_alerts (id, user_id, name, skill, experience, frequency, created_at, updated_at) "
                + "VALUES (?, ?, 'x', ?, ?, ?, now(), now())";
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), aliceId, "Java", null, "HOURLY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), aliceId, "Java", "3-4", "DAILY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), aliceId, null, null, "DAILY"))
                .isInstanceOf(DataIntegrityViolationException.class);

        create(ALICE, JAVA_ALERT);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", aliceId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM job_alerts", Long.class)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String create(String email, String body) throws Exception {
        String response = mockMvc.perform(json(post("/api/job-alerts"), body).with(as(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String body(MockHttpServletRequestBuilder request, String email) throws Exception {
        return mockMvc.perform(request.with(as(email))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private static RequestPostProcessor as(String email) {
        return user(email).roles("USER");
    }
}
