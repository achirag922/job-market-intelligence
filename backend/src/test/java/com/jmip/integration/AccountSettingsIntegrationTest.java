package com.jmip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V9.17: account settings: name, password and account deletion, each for the signed-in account only. */
@SpringBootTest(properties = {"jmip.ai.provider=stub", "jmip.alerts.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class AccountSettingsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String ALICE = "alice@example.test";
    private static final String BOB = "bob@example.test";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE notification_preferences, match_preferences, users RESTART IDENTITY CASCADE");
        UUID alice = account(ALICE, "Alice Example");
        account(BOB, "Bob Example");
        jdbcTemplate.update("INSERT INTO match_preferences (user_id, work_mode, updated_at) VALUES (?, 'REMOTE', now())", alice);
        jdbcTemplate.update("INSERT INTO notification_preferences (user_id, learning) VALUES (?, FALSE)", alice);
    }

    @Test
    @DisplayName("the name is changed for the signed-in account only")
    void changeName() throws Exception {
        send(patch("/api/account/profile"), ALICE, "{\"fullName\": \"  Alice Rivera \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Rivera"))
                .andExpect(jsonPath("$.email").value(ALICE));
        send(patch("/api/account/profile"), ALICE, "{\"fullName\": \" \"}").andExpect(status().isBadRequest());
        assertThat(name(BOB)).isEqualTo("Bob Example");
        mockMvc.perform(patch("/api/account/profile").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\": \"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the password changes only with the current password and a valid new one")
    void changePassword() throws Exception {
        send(post("/api/account/password"), ALICE, "{\"currentPassword\": \"wrong password here\", \"newPassword\": \"a brand new passphrase\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your current password is not correct"));
        send(post("/api/account/password"), ALICE, "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"short\"}")
                .andExpect(status().isBadRequest());
        send(post("/api/account/password"), ALICE, "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + PASSWORD + "\"}")
                .andExpect(status().isBadRequest());
        send(post("/api/account/password"), ALICE, "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"a brand new passphrase\"}")
                .andExpect(status().isNoContent());
        assertThat(passwordEncoder.matches("a brand new passphrase", hash(ALICE))).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, hash(BOB))).isTrue();
    }

    @Test
    @DisplayName("deleting the account needs the password and removes the account with its settings")
    void deleteAccount() throws Exception {
        send(delete("/api/account"), ALICE, "{\"password\": \"not my password\"}").andExpect(status().isBadRequest());
        assertThat(count("users")).isEqualTo(2);

        send(delete("/api/account"), ALICE, "{\"password\": \"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, ALICE)).isZero();
        assertThat(count("match_preferences")).isZero();
        assertThat(count("notification_preferences")).isZero();
        assertThat(count("users")).isEqualTo(1);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        return mockMvc.perform(request.with(user(email).roles("USER")).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String name(String email) {
        return jdbcTemplate.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, email);
    }

    private String hash(String email) {
        return jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private UUID account(String email, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at) "
                + "VALUES (?, ?, ?, ?, 'USER', now())", id, name, email, passwordEncoder.encode(PASSWORD));
        return id;
    }
}
