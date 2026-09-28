package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.5: application intelligence from the user's own tracked jobs. Alice's resume has Java.
 * Job 1 lists Java and Docker, job 2 Java, job 3 Docker and Kubernetes, job 4 Java.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class ApplicationIntelligenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE saved_job_status_events, saved_jobs, match_preferences, resume_skills, resumes, "
                + "job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems'), (2, 'Beta Labs')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM'), "
                + "(3, 'Kubernetes', 'PLATFORM')");
        job(1, "Backend Developer", 1);
        job(2, "Backend Developer", 1);
        job(3, "Platform Engineer", 1);
        job(4, "Java Developer", 2);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2), (2, 1), (3, 2), (3, 3), (4, 1)");
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Main CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resume);
    }

    @Test
    @DisplayName("with nothing tracked, every figure is zero or absent with a note saying what it needs")
    void emptyData() throws Exception {
        mockMvc.perform(get("/api/applications/insights").with(user(BOB).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracked").value(0))
                .andExpect(jsonPath("$.applications").value(0))
                .andExpect(jsonPath("$.statusCounts.OFFER").value(0))
                .andExpect(jsonPath("$.funnel.applied").value(0))
                .andExpect(jsonPath("$.funnel.interviewRate").doesNotExist())
                .andExpect(jsonPath("$.funnel.note").exists())
                .andExpect(jsonPath("$.activity", hasSize(0)))
                .andExpect(jsonPath("$.activityNote").exists())
                .andExpect(jsonPath("$.averageMatchPercentage").doesNotExist())
                .andExpect(jsonPath("$.matchNote").exists())
                .andExpect(jsonPath("$.topMissingSkills", hasSize(0)))
                .andExpect(jsonPath("$.upcomingFollowUps", hasSize(0)));
        mockMvc.perform(get("/api/applications").with(user(BOB).roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("status changes are recorded, and the funnel, averages and rankings come from them")
    void metricsAndFunnel() throws Exception {
        String one = save(1);
        String two = save(2);
        String three = save(3);
        save(4);
        move(one, "APPLIED", "INTERVIEW", "OFFER");
        move(two, "APPLIED", "INTERVIEW", "REJECTED");
        move(three, "APPLIED", "APPLIED");

        // Setting the same status again is not a change.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM saved_job_status_events WHERE saved_job_id = ?::uuid",
                Long.class, three)).isEqualTo(2);

        String month = YearMonth.now(ZoneOffset.UTC).toString();
        mockMvc.perform(get("/api/applications/insights").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.tracked").value(4))
                .andExpect(jsonPath("$.applications").value(3))
                .andExpect(jsonPath("$.statusCounts.SAVED").value(1))
                .andExpect(jsonPath("$.statusCounts.APPLIED").value(1))
                .andExpect(jsonPath("$.statusCounts.OFFER").value(1))
                .andExpect(jsonPath("$.statusCounts.REJECTED").value(1))
                // Job 2 was rejected after its interview: it still counts as interviewed.
                .andExpect(jsonPath("$.funnel.interviewed").value(2))
                .andExpect(jsonPath("$.funnel.offers").value(1))
                .andExpect(jsonPath("$.funnel.interviewRate").value(66.7))
                .andExpect(jsonPath("$.funnel.offerRate").value(33.3))
                .andExpect(jsonPath("$.activity[0].month").value(month))
                .andExpect(jsonPath("$.activity[0].saved").value(4))
                .andExpect(jsonPath("$.activity[0].applied").value(3))
                .andExpect(jsonPath("$.activity[0].interviews").value(2))
                .andExpect(jsonPath("$.activity[0].offers").value(1))
                .andExpect(jsonPath("$.activityNote").value("Trends appear once your activity spans at least two months."))
                // Applied jobs match 50%, 100% and 0% with a Java-only resume and no preferences.
                .andExpect(jsonPath("$.averageMatchPercentage").value(50.0))
                .andExpect(jsonPath("$.scoredApplications").value(3))
                .andExpect(jsonPath("$.topMissingSkills[0].name").value("Docker"))
                .andExpect(jsonPath("$.topMissingSkills[0].count").value(2))
                .andExpect(jsonPath("$.topCompanies[0].name").value("Acme Systems"))
                .andExpect(jsonPath("$.topCompanies[0].count").value(3))
                .andExpect(jsonPath("$.topRoles[0].name").value("Backend Developer"))
                .andExpect(jsonPath("$.topRoles[0].count").value(2));

        mockMvc.perform(get("/api/applications").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[?(@.job.id == 1)].overallMatchPercentage").value(50.0))
                .andExpect(jsonPath("$[?(@.job.id == 1)].missingSkills[*].name").value("Docker"))
                .andExpect(jsonPath("$[?(@.job.id == 1)].status").value("OFFER"))
                .andExpect(jsonPath("$[?(@.job.id == 4)].status").value("SAVED"));
    }

    @Test
    @DisplayName("follow-up dates split into upcoming and overdue, and can be cleared")
    void followUps() throws Exception {
        String one = save(1);
        String three = save(3);
        followUp(one, LocalDate.now().minusDays(2).toString(), "Call back").andExpect(status().isOk());
        followUp(three, LocalDate.now().plusDays(3).toString(), "Email the recruiter")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followUpOn").value(LocalDate.now().plusDays(3).toString()))
                .andExpect(jsonPath("$.followUpNote").value("Email the recruiter"));

        mockMvc.perform(get("/api/applications/insights").with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.upcomingFollowUps[*].jobId", contains(3)))
                .andExpect(jsonPath("$.upcomingFollowUps[0].note").value("Email the recruiter"))
                .andExpect(jsonPath("$.overdueFollowUps[*].jobId", contains(1)));

        followUp(three, null, "ignored without a date").andExpect(status().isOk())
                .andExpect(jsonPath("$.followUpOn").doesNotExist())
                .andExpect(jsonPath("$.followUpNote").doesNotExist());
        followUp(one, LocalDate.now().toString(), "x".repeat(201)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("applications and insights are the signed-in user's own")
    void ownership() throws Exception {
        String one = save(1);
        move(one, "APPLIED");

        mockMvc.perform(get("/api/applications/insights").with(user(BOB).roles("USER")))
                .andExpect(jsonPath("$.tracked").value(0));
        mockMvc.perform(get("/api/applications").with(user(BOB).roles("USER"))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(patch("/api/saved-jobs/" + one + "/follow-up").with(user(BOB).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"followUpOn\": \"2030-01-01\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/applications/insights")).andExpect(status().isUnauthorized());
    }

    private String save(long jobId) throws Exception {
        String body = mockMvc.perform(post("/api/jobs/" + jobId + "/save").with(user(ALICE).roles("USER")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void move(String savedJobId, String... statuses) throws Exception {
        for (String next : statuses) {
            mockMvc.perform(patch("/api/saved-jobs/" + savedJobId + "/status").with(user(ALICE).roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"" + next + "\"}"))
                    .andExpect(status().isOk());
        }
    }

    private org.springframework.test.web.servlet.ResultActions followUp(String savedJobId, String date, String note)
            throws Exception {
        String json = "{\"followUpOn\": " + (date == null ? "null" : "\"" + date + "\"") + ", \"note\": \"" + note + "\"}";
        return mockMvc.perform(patch("/api/saved-jobs/" + savedJobId + "/follow-up").with(user(ALICE).roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                """, id, email);
        return id;
    }

    private void job(long id, String title, long companyId) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint)
                VALUES (?, ?, ?, 'posting', 'itest', ?, ?)
                """, id, title, companyId, "https://example.invalid/" + id, String.format("%064d", id));
    }
}
