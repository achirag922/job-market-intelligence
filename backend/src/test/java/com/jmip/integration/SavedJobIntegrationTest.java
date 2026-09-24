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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "jmip.ai.provider=stub")
@AutoConfigureMockMvc
@Testcontainers
class SavedJobIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE saved_jobs, job_skills, jobs, companies, locations, users RESTART IDENTITY CASCADE");
        aliceId = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany')");
        for (int id = 1; id <= 2; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url,
                                      content_fingerprint, job_category, classification_confidence, classified_at)
                    VALUES (?, ?, 1, 1, 'Java and Docker', 'itest', ?, ?, 'Backend Developer', 0.9, now())
                    """, id, id == 1 ? "Backend Engineer" : "Platform Engineer", "https://example.invalid/" + id,
                    String.format("%064d", id));
        }
    }

    @Test
    @DisplayName("saving a job creates a SAVED record for the signed-in account, with the job's summary")
    void saveJob() throws Exception {
        mockMvc.perform(post("/api/jobs/1/save").with(as(ALICE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.job.id").value(1))
                .andExpect(jsonPath("$.job.title").value("Backend Engineer"))
                .andExpect(jsonPath("$.job.company.name").value("Acme Systems"))
                .andExpect(jsonPath("$.savedAt").exists())
                .andExpect(jsonPath("$.appliedAt").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM saved_jobs", UUID.class)).isEqualTo(aliceId);
    }

    @Test
    @DisplayName("saving the same job again returns the existing record; there is never a duplicate")
    void noDuplicates() throws Exception {
        String id = save(ALICE, 1);
        mockMvc.perform(patch("/api/saved-jobs/" + id + "/status").with(as(ALICE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPLIED\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/jobs/1/save").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isEqualTo(1);
        // The database refuses a second row for the same account and job, whatever the path.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO saved_jobs (id, user_id, job_id, saved_at, updated_at) VALUES (?, ?, 1, now(), now())",
                UUID.randomUUID(), aliceId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a job that does not exist cannot be saved")
    void unknownJob() throws Exception {
        mockMvc.perform(post("/api/jobs/999/save").with(as(ALICE))).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isZero();
    }

    @Test
    @DisplayName("unsaving by job id or deleting by record id removes it; unsaving twice is fine")
    void removeSavedJob() throws Exception {
        save(ALICE, 1);
        String second = save(ALICE, 2);

        mockMvc.perform(delete("/api/jobs/1/save").with(as(ALICE))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/jobs/1/save").with(as(ALICE))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/saved-jobs/" + second).with(as(ALICE))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/saved-jobs/" + second).with(as(ALICE))).andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isZero();
    }

    @Test
    @DisplayName("the list holds only the caller's saved jobs, most recently changed first, filterable by status")
    void listOwnSavedJobs() throws Exception {
        String first = save(ALICE, 1);
        String second = save(ALICE, 2);
        save(BOB, 1);
        move(first, "INTERVIEW", ALICE);

        mockMvc.perform(get("/api/saved-jobs").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second));
        mockMvc.perform(get("/api/saved-jobs").param("status", "INTERVIEW").with(as(ALICE)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].job.title").value("Backend Engineer"));
        mockMvc.perform(get("/api/saved-jobs").param("status", "OFFER").with(as(ALICE))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/saved-jobs").param("status", "HIRED").with(as(ALICE))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/saved-jobs").with(as(BOB))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("status changes set the application date on applying, keep it after, and clear it back at SAVED")
    void applicationStatus() throws Exception {
        String id = save(ALICE, 1);

        String applied = move(id, "APPLIED", ALICE);
        String appliedAt = JsonPath.read(applied, "$.appliedAt");
        assertThat(appliedAt).isNotBlank();

        for (String next : new String[]{"INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"}) {
            String body = move(id, next, ALICE);
            assertThat((String) JsonPath.read(body, "$.status")).isEqualTo(next);
            assertThat((String) JsonPath.read(body, "$.appliedAt")).isEqualTo(appliedAt);
        }

        mockMvc.perform(patch("/api/saved-jobs/" + id + "/status").with(as(ALICE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SAVED\"}"))
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.appliedAt").doesNotExist());

        for (String bad : new String[]{"{}", "{\"status\":\"HIRED\"}"}) {
            mockMvc.perform(patch("/api/saved-jobs/" + id + "/status").with(as(ALICE))
                    .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("notes can be added, replaced and cleared, and are length-limited")
    void notes() throws Exception {
        String id = save(ALICE, 1);

        mockMvc.perform(notes(id, "{\"notes\":\"  Referred by Sam; follow up Friday  \"}").with(as(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Referred by Sam; follow up Friday"));
        mockMvc.perform(notes(id, "{\"notes\":\"Second round booked\"}").with(as(ALICE)))
                .andExpect(jsonPath("$.notes").value("Second round booked"));
        mockMvc.perform(notes(id, "{\"notes\":\"" + "n".repeat(2001) + "\"}").with(as(ALICE)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(notes(id, "{\"notes\":\"   \"}").with(as(ALICE)))
                .andExpect(jsonPath("$.notes").doesNotExist());
    }

    @Test
    @DisplayName("another account's record is a 404 for every operation; its status and notes stay private")
    void cannotTouchAnotherAccountsRecord() throws Exception {
        String id = save(ALICE, 1);
        mockMvc.perform(notes(id, "{\"notes\":\"Private salary expectations\"}").with(as(ALICE))).andExpect(status().isOk());

        mockMvc.perform(patch("/api/saved-jobs/" + id + "/status").with(as(BOB)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"WITHDRAWN\"}")).andExpect(status().isNotFound());
        mockMvc.perform(notes(id, "{\"notes\":\"overwritten\"}").with(as(BOB))).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/saved-jobs/" + id).with(as(BOB))).andExpect(status().isNotFound());

        // Bob saving the same job gets his own, empty record, not Alice's.
        mockMvc.perform(post("/api/jobs/1/save").with(as(BOB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.notes").doesNotExist());
        // And unsaving it removes only his.
        mockMvc.perform(delete("/api/jobs/1/save").with(as(BOB))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/saved-jobs").with(as(ALICE)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SAVED"))
                .andExpect(jsonPath("$[0].notes").value("Private salary expectations"));
    }

    @Test
    @DisplayName("saved jobs need a signed-in user")
    void requiresSignIn() throws Exception {
        String id = save(ALICE, 1);

        mockMvc.perform(get("/api/saved-jobs")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/jobs/1/save")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/jobs/1/save")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/saved-jobs/" + id)).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting a job or an account removes its saved records; nothing is left orphaned")
    void cascades() throws Exception {
        save(ALICE, 1);
        save(ALICE, 2);
        save(BOB, 2);

        jdbcTemplate.update("DELETE FROM job_skills WHERE job_id = 2");
        jdbcTemplate.update("DELETE FROM jobs WHERE id = 2");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", aliceId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_jobs", Long.class)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String save(String email, long jobId) throws Exception {
        String body = mockMvc.perform(post("/api/jobs/" + jobId + "/save").with(as(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String move(String id, String status, String email) throws Exception {
        return mockMvc.perform(patch("/api/saved-jobs/" + id + "/status").with(as(email))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder notes(String id, String body) {
        return patch("/api/saved-jobs/" + id + "/notes").contentType(MediaType.APPLICATION_JSON).content(body);
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
