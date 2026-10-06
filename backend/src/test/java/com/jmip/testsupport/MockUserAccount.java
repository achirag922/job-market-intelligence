package com.jmip.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * A real account behind Spring's {@code @WithMockUser}, whose default username is "user".
 * Resume endpoints look the signed-in account up by that name (V6.10.4), so tests that act
 * as the mock user need the row to exist; resumes they seed directly belong to it.
 */
public final class MockUserAccount {

    /** {@code @WithMockUser}'s default username, used as the account's email. */
    public static final String USERNAME = "user";

    private MockUserAccount() {
    }

    /** Creates the account if needed and returns its id. */
    public static UUID ensure(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, role, email_verified_at)
                VALUES (?, 'Test User', ?, '{bcrypt}not-a-real-hash', 'USER', now())
                ON CONFLICT (email) DO NOTHING
                """, UUID.randomUUID(), USERNAME);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, USERNAME);
    }
}
