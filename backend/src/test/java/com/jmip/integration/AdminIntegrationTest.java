package com.jmip.integration;

import com.jayway.jsonpath.JsonPath;
import com.jmip.service.admin.AdminBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.9: the admin area. Accounts: an admin, Alice (verified user with a resume, an application
 * and an alert) and Pending (unverified). Jobs 1 and 2 come from board-a (job 2 expired), job 3
 * from board-b. Two ingestion runs are recorded: one completed (10 read, 2 rejected), one failed
 * (3 read, 1 rejected), with three rejected records whose raw input must never be shown.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false",
        "jmip.admin.bootstrap-emails=alice@example.test, pending@example.test"})
@AutoConfigureMockMvc
@Testcontainers
class AdminIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ADMIN = "admin@example.test";
    private static final String ALICE = "alice@example.test";
    private static final String PENDING = "pending@example.test";
    private static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final String SECRET_RESUME = "PRIVATE RESUME TEXT";
    private static final String SECRET_RECORD = "SECRET RAW FEED LINE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminBootstrap bootstrap;

    private UUID alice;
    private long boardB;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE batch_job_instance, batch_job_execution, batch_step_execution, etl_run_metrics, "
                + "etl_rejected_record, job_sources, saved_jobs, job_alerts, resume_skills, resumes, job_skills, jobs, "
                + "companies, users RESTART IDENTITY CASCADE");
        account(ADMIN, "ADMIN", true);
        alice = account(ALICE, "USER", true);
        account(PENDING, "USER", false);

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        job(1, "board-a", true);
        job(2, "board-a", false);
        job(3, "board-b", true);
        boardB = jdbcTemplate.queryForObject("SELECT id FROM job_sources WHERE code = 'board-b'", Long.class);

        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, extracted_text, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), ?, 'CV', TRUE, now())
                """, resume, alice, resume + ".pdf", SECRET_RESUME);
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, notes, saved_at, applied_at, updated_at)
                VALUES (?, ?, 1, 'APPLIED', 'private note', now(), now(), now())
                """, UUID.randomUUID(), alice);
        jdbcTemplate.update("""
                INSERT INTO job_alerts (id, user_id, name, keywords, frequency, active, created_at, updated_at)
                VALUES (?, ?, 'Java', 'java', 'DAILY', TRUE, now(), now())
                """, UUID.randomUUID(), alice);

        jdbcTemplate.update("INSERT INTO batch_job_instance (job_instance_id, version, job_name, job_key) "
                + "VALUES (1, 1, 'ingestJobPostings', 'k1'), (2, 1, 'ingestJobPostings', 'k2')");
        jdbcTemplate.update("""
                INSERT INTO batch_job_execution (job_execution_id, version, job_instance_id, create_time, start_time,
                                                 end_time, status, exit_code, exit_message)
                VALUES (1, 1, 1, now() - interval '2 hours', now() - interval '2 hours', now() - interval '2 hours',
                        'COMPLETED', 'COMPLETED', ''),
                       (2, 1, 2, now() - interval '1 hour', now() - interval '1 hour', now() - interval '1 hour',
                        'FAILED', 'FAILED', 'Input file not found')
                """);
        jdbcTemplate.update("""
                INSERT INTO batch_step_execution (step_execution_id, version, step_name, job_execution_id, create_time,
                                                  read_count, write_count, read_skip_count, process_skip_count, write_skip_count)
                VALUES (1, 1, 'ingestJobPostingsStep', 1, now(), 10, 7, 0, 2, 0),
                       (2, 1, 'ingestJobPostingsStep', 2, now(), 3, 0, 0, 1, 0)
                """);
        jdbcTemplate.update("INSERT INTO etl_run_metrics (job_execution_id, records_loaded, duplicates_skipped, "
                + "skill_links_created, jobs_expired) VALUES (1, 5, 2, 0, 1), (2, 0, 0, 0, 0)");
        jdbcTemplate.update("INSERT INTO etl_run_sources (job_execution_id, source_id, records_loaded, records_seen_again) "
                + "SELECT 1, id, CASE code WHEN 'board-a' THEN 3 ELSE 2 END, CASE code WHEN 'board-a' THEN 1 ELSE 0 END "
                + "FROM job_sources");
        jdbcTemplate.update("""
                INSERT INTO etl_rejected_record (job_name, job_execution_id, rejection_reason, original_record)
                VALUES ('ingestJobPostings', 1, 'missing description', ?), ('ingestJobPostings', 1, 'missing description', ?),
                       ('ingestJobPostings', 2, 'invalid status: ''maybe''', ?)
                """, SECRET_RECORD, SECRET_RECORD, SECRET_RECORD);
    }

    @Test
    @DisplayName("an admin sees users, jobs, ETL runs, sources, health and recent activity")
    void overview() throws Exception {
        mockMvc.perform(get("/api/admin/overview").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.total").value(3))
                .andExpect(jsonPath("$.users.verified").value(2))
                .andExpect(jsonPath("$.users.admins").value(1))
                .andExpect(jsonPath("$.users.newLast30Days").value(3))
                .andExpect(jsonPath("$.users.activeLast30Days").value(1))
                .andExpect(jsonPath("$.users.activeDefinition", containsString("Sign-ins are not recorded")))
                .andExpect(jsonPath("$.jobs.total").value(3))
                .andExpect(jsonPath("$.jobs.active").value(2))
                .andExpect(jsonPath("$.jobs.inactive").value(1))
                .andExpect(jsonPath("$.jobs.firstSeenLast7Days").value(3))
                .andExpect(jsonPath("$.etl.recent", hasSize(2)))
                .andExpect(jsonPath("$.etl.latest.executionId").value(2))
                .andExpect(jsonPath("$.etl.latest.outcome").value("FAILED"))
                .andExpect(jsonPath("$.etl.failedLast30Days").value(1))
                .andExpect(jsonPath("$.sources[*].code", contains("board-a", "board-b")))
                .andExpect(jsonPath("$.health.status").value("UP"))
                .andExpect(jsonPath("$.health.components.db").value("UP"))
                .andExpect(jsonPath("$.activity.days").value(7))
                .andExpect(jsonPath("$.activity.signups").value(3))
                .andExpect(jsonPath("$.activity.jobsSaved").value(1))
                .andExpect(jsonPath("$.activity.applications").value(1));
    }

    @Test
    @DisplayName("V8.2 data quality: totals, current job status, rejection reasons (never raw records) and per source")
    void dataQuality() throws Exception {
        String body = mockMvc.perform(get("/api/admin/data-quality").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.ingestionRuns").value(2))
                .andExpect(jsonPath("$.totals.recordsRead").value(13))
                .andExpect(jsonPath("$.totals.validRecords").value(10))
                .andExpect(jsonPath("$.totals.rejected").value(3))
                .andExpect(jsonPath("$.totals.loaded").value(5))
                .andExpect(jsonPath("$.totals.duplicates").value(2))
                .andExpect(jsonPath("$.totals.expired").value(1))
                .andExpect(jsonPath("$.current.jobs").value(3))
                .andExpect(jsonPath("$.current.inactive").value(1))
                .andExpect(jsonPath("$.current.expiredByDate").value(1))
                .andExpect(jsonPath("$.current.closedBySource").value(0))
                .andExpect(jsonPath("$.topRejectionReasons[0].reason").value("missing description"))
                .andExpect(jsonPath("$.topRejectionReasons[0].count").value(2))
                .andExpect(jsonPath("$.sources[0].code").value("board-a"))
                .andExpect(jsonPath("$.sources[0].jobs").value(2))
                .andExpect(jsonPath("$.sources[0].activeJobs").value(1))
                .andExpect(jsonPath("$.sources[0].inactiveJobs").value(1))
                .andExpect(jsonPath("$.sources[0].loaded").value(3))
                .andExpect(jsonPath("$.sources[0].seenAgain").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET_RECORD);
    }

    @Test
    @DisplayName("user management: list, search and filter, and one account's metadata without anything sensitive")
    void users() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content", hasSize(3)));
        mockMvc.perform(get("/api/admin/users").param("q", "ALI").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(jsonPath("$.content[*].email", contains(ALICE)));
        mockMvc.perform(get("/api/admin/users").param("role", "ADMIN").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(jsonPath("$.content[*].email", contains(ADMIN)));
        mockMvc.perform(get("/api/admin/users").param("verified", "false").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(jsonPath("$.content[*].email", contains(PENDING)));
        mockMvc.perform(get("/api/admin/users").param("role", "ROOT").with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(status().isBadRequest());

        String list = mockMvc.perform(get("/api/admin/users").with(user(ADMIN).roles("ADMIN", "USER")))
                .andReturn().getResponse().getContentAsString();
        String detail = mockMvc.perform(get("/api/admin/users/" + alice).with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(ALICE))
                .andExpect(jsonPath("$.account.emailVerified").value(true))
                .andExpect(jsonPath("$.resumes").value(1))
                .andExpect(jsonPath("$.savedJobs").value(1))
                .andExpect(jsonPath("$.applications").value(1))
                .andExpect(jsonPath("$.jobAlerts").value(1))
                .andExpect(jsonPath("$.lastActivityAt").exists())
                .andExpect(jsonPath("$.note", containsString("cannot be deactivated")))
                .andReturn().getResponse().getContentAsString();
        for (String body : new String[]{list, detail}) {
            assertThat(body).doesNotContainIgnoringCase("password").doesNotContain("{bcrypt}", "$2a$", SECRET_RESUME,
                    "private note");
        }
        mockMvc.perform(get("/api/admin/users/" + UUID.randomUUID()).with(user(ADMIN).roles("ADMIN", "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an admin can switch a job source off; users still only read sources")
    void jobSourceSwitch() throws Exception {
        mockMvc.perform(patch("/api/admin/job-sources/" + boardB).with(user(ADMIN).roles("ADMIN", "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("board-b"))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM job_sources WHERE id = ?", Boolean.class, boardB)).isFalse();
        mockMvc.perform(get("/api/job-sources/" + boardB).with(user(ALICE).roles("USER")))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(patch("/api/admin/job-sources/" + boardB).with(user(ADMIN).roles("ADMIN", "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/admin/job-sources/99999").with(user(ADMIN).roles("ADMIN", "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a USER gets 403 on every admin API, and a signed-out caller 401")
    void userDenied() throws Exception {
        for (String path : new String[]{"/api/admin/overview", "/api/admin/data-quality", "/api/admin/users",
                "/api/admin/users/" + alice}) {
            mockMvc.perform(get(path).with(user(ALICE).roles("USER"))).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(patch("/api/admin/job-sources/" + boardB).with(user(ALICE).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM job_sources WHERE id = ?", Boolean.class, boardB)).isTrue();
    }

    @Test
    @DisplayName("roles come from the account at sign-in: an admin keeps user access, CSRF still applies, a user is refused")
    void realSessions() throws Exception {
        MvcResult adminLogin = login(ADMIN);
        MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);
        String csrf = JsonPath.read(adminLogin.getResponse().getContentAsString(), "$.csrfToken");
        assertThat(JsonPath.<String>read(adminLogin.getResponse().getContentAsString(), "$.user.role")).isEqualTo("ADMIN");

        mockMvc.perform(get("/api/admin/overview").with(signedIn(adminSession))).andExpect(status().isOk());
        mockMvc.perform(get("/api/saved-jobs").with(signedIn(adminSession))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/job-sources/" + boardB).with(signedIn(adminSession))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/job-sources/" + boardB).with(signedIn(adminSession)).header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk());

        MockHttpSession aliceSession = (MockHttpSession) login(ALICE).getRequest().getSession(false);
        mockMvc.perform(get("/api/admin/users").with(signedIn(aliceSession))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/saved-jobs").with(signedIn(aliceSession))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("signup never creates an admin; only verified accounts listed in JMIP_ADMIN_EMAILS are promoted")
    void assigningAdmins() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName": "Mallory", "email": "mallory@example.test", "password": "%s", "role": "ADMIN"}
                        """.formatted(PASSWORD)))
                .andExpect(status().is2xxSuccessful());
        assertThat(role("mallory@example.test")).isEqualTo("USER");

        assertThat(bootstrap.promote()).isEqualTo(1);
        assertThat(role(ALICE)).isEqualTo("ADMIN");
        assertThat(role(PENDING)).isEqualTo("USER");
        assertThat(bootstrap.promote()).isZero();
    }

    /** As a browser would send it: the session cookie is a valid requested session id, so CSRF applies. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor signedIn(MockHttpSession session) {
        return request -> {
            request.setSession(session);
            request.setRequestedSessionId(session.getId());
            request.setRequestedSessionIdValid(true);
            return request;
        };
    }

    private MvcResult login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private String role(String email) {
        return jdbcTemplate.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
    }

    private UUID account(String email, String role, boolean verified) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test', ?, ?, ?, CASE WHEN ? THEN now() END)
                """, id, email, passwordEncoder.encode(PASSWORD), role, verified);
        return id;
    }

    private void job(long id, String source, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint,
                                  active, deactivated_at, expires_at, posted_date)
                VALUES (?, 'Engineer', 1, 'posting', ?, ?, ?, ?, CASE WHEN ? THEN NULL ELSE now() END,
                        CASE WHEN ? THEN NULL ELSE current_date - 1 END, current_date - 30)
                """, id, source, "https://" + source + ".example/" + id, String.format("%064d", id), active, active, active);
    }
}
