package com.jmip.repository;

import com.jmip.dto.resume.MatchPreferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** V8.3: one row of match preferences per account, always read and written by owner id. */
@Repository
public class MatchPreferencesRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchPreferencesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The owner's preferences, or {@link MatchPreferences#NONE} when none are saved. */
    public MatchPreferences find(UUID userId) {
        return jdbcTemplate.query("""
                SELECT years_experience, preferred_location, work_mode, min_salary, salary_currency
                  FROM match_preferences WHERE user_id = ?
                """, (rs, row) -> new MatchPreferences(rs.getObject(1, Integer.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getString(5)), userId).stream().findFirst().orElse(MatchPreferences.NONE);
    }

    public void save(UUID userId, MatchPreferences preferences) {
        jdbcTemplate.update("""
                INSERT INTO match_preferences (user_id, years_experience, preferred_location, work_mode, min_salary,
                                               salary_currency, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id) DO UPDATE
                   SET years_experience = EXCLUDED.years_experience,
                       preferred_location = EXCLUDED.preferred_location,
                       work_mode = EXCLUDED.work_mode,
                       min_salary = EXCLUDED.min_salary,
                       salary_currency = EXCLUDED.salary_currency,
                       updated_at = now()
                """, userId, preferences.yearsExperience(), preferences.preferredLocation(), preferences.workMode(),
                preferences.minSalary(), preferences.salaryCurrency());
    }
}
