package com.jmip.repository;

import com.jmip.dto.resume.MatchPreferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** V8.3: one row of match preferences per account, always read and written by owner id. V9.2 adds the lists. */
@Repository
public class MatchPreferencesRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchPreferencesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The owner's preferences, or {@link MatchPreferences#NONE} when none are saved. */
    public MatchPreferences find(UUID userId) {
        return jdbcTemplate.query("""
                SELECT years_experience, preferred_location, work_mode, min_salary, salary_currency,
                       preferred_categories, preferred_skills, excluded_companies, excluded_locations
                  FROM match_preferences WHERE user_id = ?
                """, (rs, row) -> new MatchPreferences(rs.getObject(1, Integer.class), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getString(5), list(rs.getArray(6)), list(rs.getArray(7)), list(rs.getArray(8)),
                list(rs.getArray(9))), userId).stream().findFirst().orElse(MatchPreferences.NONE);
    }

    public void save(UUID userId, MatchPreferences preferences) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO match_preferences (user_id, years_experience, preferred_location, work_mode, min_salary,
                                                   salary_currency, preferred_categories, preferred_skills,
                                                   excluded_companies, excluded_locations, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (user_id) DO UPDATE
                       SET years_experience = EXCLUDED.years_experience,
                           preferred_location = EXCLUDED.preferred_location,
                           work_mode = EXCLUDED.work_mode,
                           min_salary = EXCLUDED.min_salary,
                           salary_currency = EXCLUDED.salary_currency,
                           preferred_categories = EXCLUDED.preferred_categories,
                           preferred_skills = EXCLUDED.preferred_skills,
                           excluded_companies = EXCLUDED.excluded_companies,
                           excluded_locations = EXCLUDED.excluded_locations,
                           updated_at = now()
                    """);
            statement.setObject(1, userId);
            statement.setObject(2, preferences.yearsExperience());
            statement.setString(3, preferences.preferredLocation());
            statement.setString(4, preferences.workMode());
            statement.setBigDecimal(5, preferences.minSalary());
            statement.setString(6, preferences.salaryCurrency());
            statement.setArray(7, connection.createArrayOf("text", preferences.preferredCategories().toArray()));
            statement.setArray(8, connection.createArrayOf("text", preferences.preferredSkills().toArray()));
            statement.setArray(9, connection.createArrayOf("text", preferences.excludedCompanies().toArray()));
            statement.setArray(10, connection.createArrayOf("text", preferences.excludedLocations().toArray()));
            return statement;
        });
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
