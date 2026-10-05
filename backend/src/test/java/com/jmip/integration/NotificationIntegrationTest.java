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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9.16: notifications derived from existing data. Alice has a job alert with two recorded matches,
 * an applied job whose follow-up was due yesterday, a job at the interview stage and a learning item
 * due tomorrow. Bob has nothing.
 */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class NotificationIntegrationTest {

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
        jdbcTemplate.execute("TRUNCATE notifications, notification_preferences, job_alert_notifications, job_alerts, learning_items, "
                + "saved_jobs, jobs, companies, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE);
        account(BOB);
        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme')");
        for (int id = 1; id <= 3; id++) {
            jdbcTemplate.update("INSERT INTO jobs (id, title, company_id, description, source, source_url, content_fingerprint) "
                    + "VALUES (?, ?, 1, 'posting', 'itest', ?, ?)", id, "Engineer " + id, "https://example.invalid/" + id,
                    String.format("%064d", id));
        }
        UUID alert = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO job_alerts (id, user_id, name, keywords, frequency, active, created_at, updated_at) "
                + "VALUES (?, ?, 'Java roles', 'java', 'DAILY', TRUE, now(), now())", alert, alice);
        jdbcTemplate.update("INSERT INTO job_alert_notifications (alert_id, user_id, job_id) VALUES (?, ?, 1), (?, ?, 2)",
                alert, alice, alert, alice);
        jdbcTemplate.update("""
                INSERT INTO saved_jobs (id, user_id, job_id, status, saved_at, applied_at, updated_at, follow_up_on, follow_up_note)
                VALUES (?, ?, 1, 'APPLIED', now() - interval '20 days', now(), now(), current_date - 1, 'Email the recruiter'),
                       (?, ?, 2, 'INTERVIEW', now() - interval '20 days', now(), now(), NULL, NULL)
                """, UUID.randomUUID(), alice, UUID.randomUUID(), alice);
        jdbcTemplate.update("""
                INSERT INTO learning_items (id, user_id, skill_name, topic, priority, status, progress, target_date, created_at, updated_at)
                VALUES (?, ?, 'Docker', 'Compose basics', 'HIGH', 'IN_PROGRESS', 40, current_date + 1, now(), now())
                """, UUID.randomUUID(), alice);
    }

    @Test
    @DisplayName("the inbox gathers job matches, follow-ups, interviews and learning reminders, each once, unread first")
    void inboxFromExistingData() throws Exception {
        inbox(ALICE, "")
                .andExpect(jsonPath("$.items[?(@.type == 'JOB_MATCH')].title", contains("2 new jobs match “Java roles”")))
                .andExpect(jsonPath("$.items[?(@.type == 'FOLLOW_UP')].title", contains("Follow-up overdue: Engineer 1")))
                .andExpect(jsonPath("$.items[?(@.type == 'INTERVIEW')].title", contains("Interview stage: Engineer 2")))
                .andExpect(jsonPath("$.items[?(@.type == 'LEARNING')].title", contains("Learning target soon: Docker")))
                .andExpect(jsonPath("$.items[*].readAt").isEmpty())
                .andExpect(jsonPath("$.items[0].createdAt").exists());
        int count = JsonPath.read(inbox(ALICE, "").andReturn().getResponse().getContentAsString(), "$.unreadCount");
        assertThat(count).isGreaterThanOrEqualTo(4);
        // Deriving again creates nothing new.
        jdbcTemplate.update("UPDATE notification_preferences SET refreshed_at = NULL");
        inbox(ALICE, "").andExpect(jsonPath("$.unreadCount").value(count));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isEqualTo(count);
    }

    @Test
    @DisplayName("notifications are marked read one by one or all at once, and only by their owner")
    void readStateAndOwnership() throws Exception {
        String body = inbox(ALICE, "").andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.items[0].id");
        int unread = JsonPath.read(body, "$.unreadCount");

        send(post("/api/notifications/" + id + "/read"), BOB, null).andExpect(status().isNotFound());
        send(post("/api/notifications/" + id + "/read"), ALICE, null).andExpect(status().isNoContent());
        send(get("/api/notifications/unread-count"), ALICE, null).andExpect(jsonPath("$.unreadCount").value(unread - 1));
        inbox(ALICE, "?unreadOnly=true").andExpect(jsonPath("$.items[*].id", not(hasItem(id))));

        send(post("/api/notifications/read-all"), ALICE, null).andExpect(jsonPath("$.unreadCount").value(0));
        inbox(ALICE, "").andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.items[*].readAt", everyItem(org.hamcrest.Matchers.notNullValue())));

        inbox(BOB, "").andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.unreadCount").value(0));
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("preferences turn kinds of notification off and on")
    void preferences() throws Exception {
        send(get("/api/notifications/preferences"), ALICE, null).andExpect(jsonPath("$.jobMatches").value(true));
        send(put("/api/notifications/preferences"), ALICE,
                "{\"jobMatches\": false, \"followUps\": true, \"interviews\": false, \"learning\": true, \"career\": true}")
                .andExpect(jsonPath("$.jobMatches").value(false));
        String body = inbox(ALICE, "").andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(body, "$.items[*].type");
        assertThat(types).doesNotContain("JOB_MATCH", "INTERVIEW").contains("FOLLOW_UP", "LEARNING");
        send(put("/api/notifications/preferences"), ALICE, "{\"jobMatches\": true}").andExpect(status().isBadRequest());
        // Bob's preferences are his own.
        send(get("/api/notifications/preferences"), BOB, null).andExpect(jsonPath("$.jobMatches").value(true));
    }

    private ResultActions inbox(String email, String query) throws Exception {
        return mockMvc.perform(get("/api/notifications" + query).with(user(email).roles("USER"))).andExpect(status().isOk());
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
