package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import com.jmip.service.workspace.FollowUpReminderJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.14: the job-search workspace. Five Backend Developer jobs: 1 asks Java and SQL (newest), 2 Java,
 * 3 Docker, 4 SQL and Docker, 5 Docker (oldest). Alice's resume has Java and SQL; Bob has no resume.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FollowUpReminderJob reminders;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE saved_searches, job_views, hidden_jobs, saved_job_status_events, saved_jobs, match_preferences, "
                + "resume_skills, resumes, job_skills, jobs, skills, companies, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'SQL', 'LANGUAGE'), "
                + "(3, 'Docker', 'PLATFORM')");
        for (int id = 1; id <= 5; id++) {
            jdbcTemplate.update("""
                    INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                      job_category, classification_confidence, classified_at, posted_date)
                    VALUES (?, ?, 1, 'posting', 'itest', ?, ?, 'Backend Developer', 0.9, now(), current_date - ?)
                    """, id, "Engineer " + id, "https://example.invalid/" + id, String.format("%064d", id), id);
        }
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (3, 3), (4, 2), (4, 3), (5, 3)");
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1), (?, 2)", resume, resume);
    }

    @Test
    @DisplayName("a hidden job leaves recommendations and, when asked, search results; plain search is unchanged")
    void hideAndUnhide() throws Exception {
        send(post("/api/jobs/3/hide"), ALICE, null).andExpect(status().isNoContent());
        send(post("/api/jobs/3/hide"), ALICE, null).andExpect(status().isNoContent()); // idempotent
        search(ALICE, "excludeHidden=true").andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(3))));
        search(ALICE, "").andExpect(jsonPath("$.totalElements").value(5));
        search(BOB, "excludeHidden=true").andExpect(jsonPath("$.totalElements").value(5));
        send(get("/api/jobs/personalized?limit=10"), ALICE, null).andExpect(jsonPath("$.jobs[*].job.id", not(hasItem(3))));

        send(post("/api/jobs/999/hide"), ALICE, null).andExpect(status().isNotFound());
        send(delete("/api/jobs/3/hide"), BOB, null).andExpect(status().isNotFound());
        send(delete("/api/jobs/3/hide"), ALICE, null).andExpect(status().isNoContent());
        search(ALICE, "excludeHidden=true").andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    @DisplayName("results can be ordered by the existing skill match, with explanations per job; both need a resume")
    void matchOrderAndHints() throws Exception {
        search(ALICE, "order=match&size=2")
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        search(ALICE, "order=match&size=2&page=2").andExpect(jsonPath("$.content", hasSize(1)));
        send(get("/api/workspace/matches?jobIds=1,4"), ALICE, null)
                .andExpect(jsonPath("$.matches['1'].percentage").value(100.0))
                .andExpect(jsonPath("$.matches['4'].matched", contains("SQL")))
                .andExpect(jsonPath("$.matches['4'].missing", contains("Docker")))
                .andExpect(jsonPath("$.note").doesNotExist());
        send(get("/api/workspace/matches?jobIds=1"), BOB, null)
                .andExpect(jsonPath("$.matches").isEmpty())
                .andExpect(jsonPath("$.note").exists());
        search(BOB, "order=match").andExpect(status().isBadRequest());
        // The named orderings still work as before.
        search(ALICE, "order=newest").andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    @DisplayName("searches are saved with only search filters, unique by name, and only for their owner")
    void savedSearches() throws Exception {
        String id = JsonPath.read(send(post("/api/workspace/searches"), ALICE,
                        "{\"name\": \"Remote Java\", \"filters\": {\"q\": \"java\", \"category\": \"Backend Developer\", \"order\": \"match\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filters.q").value("java"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        send(post("/api/workspace/searches"), ALICE, "{\"name\": \"remote java\", \"filters\": {\"q\": \"x\"}}")
                .andExpect(status().isConflict());
        send(post("/api/workspace/searches"), ALICE, "{\"name\": \"Odd\", \"filters\": {\"userId\": \"x\"}}")
                .andExpect(status().isBadRequest());
        send(post("/api/workspace/searches"), ALICE, "{\"name\": \" \", \"filters\": {}}").andExpect(status().isBadRequest());

        send(get("/api/workspace/searches"), ALICE, null).andExpect(jsonPath("$", hasSize(1)));
        send(get("/api/workspace/searches"), BOB, null).andExpect(jsonPath("$", hasSize(0)));
        send(delete("/api/workspace/searches/" + id), BOB, null).andExpect(status().isNotFound());
        send(delete("/api/workspace/searches/" + id), ALICE, null).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspace/searches")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the workspace gathers saved, applied, follow-ups with priority, recently viewed and hidden jobs; reminders go once")
    void workspaceAndFollowUps() throws Exception {
        String saved = JsonPath.read(send(post("/api/jobs/2/save"), ALICE, null).andReturn().getResponse().getContentAsString(), "$.id");
        send(post("/api/jobs/4/save"), ALICE, null).andExpect(status().is2xxSuccessful());
        send(patch("/api/saved-jobs/" + saved + "/status"), ALICE, "{\"status\": \"APPLIED\"}").andExpect(status().isOk());
        send(patch("/api/saved-jobs/" + saved + "/priority"), ALICE, "{\"priority\": \"HIGH\"}")
                .andExpect(jsonPath("$.priority").value("HIGH"));
        send(patch("/api/saved-jobs/" + saved + "/priority"), ALICE, "{\"priority\": \"URGENT\"}").andExpect(status().isBadRequest());
        send(patch("/api/saved-jobs/" + saved + "/priority"), BOB, "{\"priority\": \"LOW\"}").andExpect(status().isNotFound());
        send(patch("/api/saved-jobs/" + saved + "/follow-up"), ALICE,
                "{\"followUpOn\": \"" + LocalDate.now().minusDays(1) + "\", \"note\": \"Email the recruiter\"}").andExpect(status().isOk());
        send(post("/api/jobs/3/viewed"), ALICE, null).andExpect(status().isNoContent());
        send(post("/api/jobs/1/viewed"), ALICE, null).andExpect(status().isNoContent());
        send(post("/api/jobs/5/hide"), ALICE, null).andExpect(status().isNoContent());

        send(get("/api/workspace"), ALICE, null)
                .andExpect(jsonPath("$.saved[*].job.id", contains(4)))
                .andExpect(jsonPath("$.applied[*].job.id", contains(2)))
                .andExpect(jsonPath("$.applied[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.followUps[0].followUpNote").value("Email the recruiter"))
                .andExpect(jsonPath("$.recentlyViewed[*].job.id", contains(1, 3)))
                .andExpect(jsonPath("$.hidden[*].job.id", contains(5)))
                .andExpect(jsonPath("$.recommended[*].job.id", not(hasItem(5))));
        send(get("/api/workspace"), BOB, null)
                .andExpect(jsonPath("$.saved", hasSize(0)))
                .andExpect(jsonPath("$.followUps", hasSize(0)))
                .andExpect(jsonPath("$.recentlyViewed", hasSize(0)))
                .andExpect(jsonPath("$.hidden", hasSize(0)));
        send(post("/api/jobs/999/viewed"), ALICE, null).andExpect(status().isNotFound());

        // The due follow-up is emailed once (through the alert delivery, log mode here), then not again.
        assertThat(reminders.sendDue()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT follow_up_reminded_on FROM saved_jobs WHERE id = ?::uuid", LocalDate.class, saved))
                .isEqualTo(LocalDate.now().minusDays(1));
        assertThat(reminders.sendDue()).isZero();
        // A new date makes it eligible again once it is due.
        send(patch("/api/saved-jobs/" + saved + "/follow-up"), ALICE, "{\"followUpOn\": \"" + LocalDate.now() + "\"}").andExpect(status().isOk());
        assertThat(reminders.sendDue()).isEqualTo(1);
    }

    private ResultActions search(String email, String query) throws Exception {
        return mockMvc.perform(get("/api/jobs?" + query).with(user(email).roles("USER")));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        MockHttpServletRequestBuilder signedIn = request.with(user(email).roles("USER"));
        return mockMvc.perform(json == null ? signedIn : signedIn.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }
}
