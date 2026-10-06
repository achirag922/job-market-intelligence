package com.jmip.integration;

import com.jmip.service.alert.AlertDigest;
import com.jmip.service.alert.AlertEmailSender;
import com.jmip.service.alert.JobAlertProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V8.4: the scheduled digest pass, run directly. Alice (verified, resume with Java) has a daily
 * "java" alert created two days ago. Job 1 (Java, first seen an hour ago) is new and matches;
 * job 2 does not match; job 3 was seen before the alert existed; job 4 is inactive.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class JobAlertDigestIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";

    /** Captures digests instead of emailing them; can be told to fail. */
    static final class FakeSender implements AlertEmailSender {
        final List<Map.Entry<String, AlertDigest>> sent = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void send(String to, AlertDigest digest) {
            if (failing) {
                throw new MailSendException("smtp down");
            }
            sent.add(Map.entry(to, digest));
        }
    }

    static final FakeSender SENDER = new FakeSender();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        AlertEmailSender fakeAlertEmailSender() {
            return SENDER;
        }
    }

    @Autowired
    private JobAlertProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private UUID alice;
    private UUID aliceAlert;

    @BeforeEach
    void seed() {
        SENDER.sent.clear();
        SENDER.failing = false;
        jdbcTemplate.execute("TRUNCATE job_alert_notifications, job_alerts, match_preferences, resume_skills, resumes, "
                + "job_skills, jobs, skills, companies, locations, users RESTART IDENTITY CASCADE");
        alice = account(ALICE, true);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO locations (id, city, country) VALUES (1, 'Berlin', 'Germany')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES (1, 'Java', 'LANGUAGE'), (2, 'Docker', 'PLATFORM')");
        job(1, "Java Developer", "Build services. Fully remote.", "1 hour", true);
        job(2, "Python Developer", "Data work.", "1 hour", true);
        job(3, "Java Engineer", "Older posting.", "3 days", true);
        job(4, "Java Lead", "Closed posting.", "1 hour", false);
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (1, 1), (1, 2)");
        UUID resume = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resumes (id, user_id, original_file_name, stored_file_name, content_type, file_size_bytes,
                                     processing_status, processed_at, title, is_default, updated_at)
                VALUES (?, ?, 'cv.pdf', ?, 'application/pdf', 100, 'COMPLETED', now(), 'Main CV', TRUE, now())
                """, resume, alice, resume + ".pdf");
        jdbcTemplate.update("INSERT INTO resume_skills (resume_id, skill_id) VALUES (?, 1)", resume);
        aliceAlert = alert(alice, "Java jobs", "DAILY", true, "2 days");
    }

    @Test
    @DisplayName("a due alert emails one digest of its new active matches, with match score and link, and never repeats a job")
    void digestOnce() {
        JobAlertProcessor.Result result = processor.processDue();

        assertThat(result.digestsSent()).isEqualTo(1);
        assertThat(SENDER.sent).hasSize(1);
        Map.Entry<String, AlertDigest> email = SENDER.sent.get(0);
        assertThat(email.getKey()).isEqualTo(ALICE);
        assertThat(email.getValue().subject()).isEqualTo("JMIP daily digest: 1 new job for \"Java jobs\"");
        assertThat(email.getValue().body())
                .contains("Java Developer — Acme Systems", "Berlin, Germany · Remote", "Match: 50%",
                        "http://localhost:5173/jobs/1", "/alerts")
                .doesNotContain("Python", "Java Engineer", "Java Lead", "password", "bcrypt", alice.toString());
        assertThat(jdbcTemplate.queryForMap("SELECT status, sent_at IS NOT NULL AS sent, match_percentage FROM job_alert_notifications"))
                .containsEntry("status", "SENT").containsEntry("sent", true);

        // Not due again today, and when it is, job 1 is not sent a second time.
        assertThat(processor.processDue().dueAlerts()).isZero();
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = now() - interval '2 days'");
        processor.processDue();
        assertThat(SENDER.sent).hasSize(1);
        assertThat(count("SELECT count(*) FROM job_alert_notifications")).isEqualTo(1);
    }

    @Test
    @DisplayName("weekly alerts wait a week; paused alerts and unverified accounts are skipped")
    void frequencyAndInactive() {
        jdbcTemplate.update("DELETE FROM job_alerts");
        UUID weekly = alert(alice, "Weekly java", "WEEKLY", true, "10 days");
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = now() - interval '2 days' WHERE id = ?", weekly);
        alert(alice, "Paused java", "DAILY", false, "2 days");
        alert(account(BOB, false), "Unverified", "DAILY", true, "2 days");

        assertThat(processor.processDue().dueAlerts()).isZero();

        // A week later: everything first seen in that week (jobs 1 and 3) that matches.
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = now() - interval '8 days' WHERE id = ?", weekly);
        JobAlertProcessor.Result result = processor.processDue();
        assertThat(result.dueAlerts()).isEqualTo(1);
        assertThat(SENDER.sent).extracting(e -> e.getValue().subject()).containsExactly("JMIP weekly digest: 2 new jobs for \"Weekly java\"");
    }

    @Test
    @DisplayName("a failed delivery is recorded and retried on the next pass, up to the attempt limit")
    void failureAndRetry() {
        SENDER.failing = true;
        JobAlertProcessor.Result failed = processor.processDue();

        assertThat(failed.digestsFailed()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT status, attempts, last_error FROM job_alert_notifications"))
                .containsEntry("status", "FAILED").containsEntry("attempts", 1).containsEntry("last_error", "MailSendException");

        SENDER.failing = false;
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = now() - interval '2 days'");
        processor.processDue();
        assertThat(SENDER.sent).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM job_alert_notifications", String.class)).isEqualTo("SENT");

        // After the limit a failed job is left as FAILED rather than retried for ever.
        jdbcTemplate.update("UPDATE job_alert_notifications SET status = 'FAILED', sent_at = NULL, attempts = 3");
        jdbcTemplate.update("UPDATE job_alerts SET last_processed_at = now() - interval '2 days'");
        processor.processDue();
        assertThat(SENDER.sent).hasSize(1);
    }

    @Test
    @DisplayName("the owner sees what an alert sent; others cannot; a resumed alert starts from now")
    void ownerViewAndResume() throws Exception {
        processor.processDue();

        mockMvc.perform(get("/api/job-alerts/" + aliceAlert + "/notifications").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].jobTitle").value("Java Developer"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
        account(BOB, true);
        mockMvc.perform(get("/api/job-alerts/" + aliceAlert + "/notifications").with(user(BOB).roles("USER")))
                .andExpect(status().isNotFound());

        jdbcTemplate.update("UPDATE job_alerts SET active = FALSE, last_processed_at = now() - interval '5 days'");
        mockMvc.perform(patch("/api/job-alerts/" + aliceAlert + "/status").with(user(ALICE).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastProcessedAt").exists());
        assertThat(processor.processDue().dueAlerts()).isZero();
    }

    private UUID account(String email, boolean verified) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Alice', ?, '{bcrypt}not-a-real-hash', 'USER', CASE WHEN ? THEN now() END)
                """, id, email, verified);
        return id;
    }

    private UUID alert(UUID owner, String name, String frequency, boolean active, String age) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO job_alerts (id, user_id, name, keywords, frequency, active, created_at, updated_at)
                VALUES (?, ?, ?, 'java', ?, ?, now() - ?::interval, now() - ?::interval)
                """, id, owner, name, frequency, active, age, age);
        return id;
    }

    private void job(long id, String title, String description, String age, boolean active) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, location_id, description, source, source_url, content_fingerprint,
                                  first_seen_at, last_seen_at, active, deactivated_at)
                VALUES (?, ?, 1, 1, ?, 'itest', ?, ?, now() - ?::interval, now(), ?, CASE WHEN ? THEN NULL ELSE now() END)
                """, id, title, description, "https://example.invalid/" + id, String.format("%064d", id), age, active, active);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
