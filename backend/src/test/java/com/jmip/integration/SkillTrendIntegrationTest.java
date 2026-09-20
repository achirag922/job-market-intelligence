package com.jmip.integration;

import com.jmip.repository.SkillTrendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Skill trends against a real PostgreSQL, over a fixture with a deliberate rise and fall.
 *
 * <p>Four months, 10 postings each, so every share is a round number and any arithmetic
 * error is obvious:
 *
 * <pre>
 * period    total  Kubernetes  Java  Python
 * 2026-06-01   10           1     6       3   Kubernetes 10%, Java 60%
 * 2026-07-01   10           2     5       3
 * 2026-08-01   10           5     2       3
 * 2026-09-01   10           6     1       3   Kubernetes 60%, Java 10%
 * </pre>
 *
 * <p>Each row sums to the month's total, so every posting carries exactly one skill and
 * the counts are exactly what the table says.
 *
 * <p>Pooled halves: Kubernetes goes 15% to 55% (+40 points), Java 55% to 15% (-40 points),
 * Python stays at 30%. Postings per month are held constant on purpose — it isolates the
 * change in mix from any change in volume.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "jmip.analytics.snapshots.refresh-on-startup=false")
class SkillTrendIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final int[][] SKILL_COUNTS = {
            // {kubernetes, java, python} per month, June to September. Each row must sum
            // to the 10 postings that month, or a skill silently gets fewer than stated.
            {1, 6, 3},
            {2, 5, 3},
            {5, 2, 3},
            {6, 1, 3},
    };

    private static final String[] MONTHS = {"2026-06", "2026-07", "2026-08", "2026-09"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SkillTrendRepository skillTrendRepository;

    @BeforeEach
    void seed() {
        jdbcTemplate.execute(
                "TRUNCATE skill_demand_snapshot, job_skills, jobs, skills, companies, locations "
                        + "RESTART IDENTITY CASCADE");

        jdbcTemplate.update("INSERT INTO companies (id, name) VALUES (1, 'Acme Systems')");
        jdbcTemplate.update("INSERT INTO skills (id, name, category) VALUES "
                + "(1, 'Kubernetes', 'PLATFORM'), (2, 'Java', 'LANGUAGE'), (3, 'Python', 'LANGUAGE')");

        long jobId = 1;
        for (int month = 0; month < MONTHS.length; month++) {
            int[] counts = SKILL_COUNTS[month];
            for (int slot = 0; slot < 10; slot++) {
                String postedDate = MONTHS[month] + "-1" + (slot % 10);
                jdbcTemplate.update("""
                                INSERT INTO jobs (id, title, company_id, description, posted_date,
                                                  source, source_url, content_fingerprint)
                                VALUES (?, ?, 1, 'Description', ?::date, 'itest', ?, ?)
                                """,
                        jobId, "Engineer " + jobId, postedDate,
                        "https://example.invalid/jobs/" + jobId, String.format("%064d", jobId));

                // Skills are assigned so that each month hits the counts in the table above.
                if (slot < counts[0]) {
                    link(jobId, 1);
                } else if (slot < counts[0] + counts[1]) {
                    link(jobId, 2);
                } else if (slot < counts[0] + counts[1] + counts[2]) {
                    link(jobId, 3);
                }
                jobId++;
            }
        }

        skillTrendRepository.rebuild();
    }

    @Test
    @DisplayName("the rebuild writes one snapshot row per skill per period")
    void rebuildWritesHistory() {
        // Three skills across four months, each skill present every month.
        assertThat(skillTrendRepository.countSnapshots()).isEqualTo(12);
        assertThat(skillTrendRepository.findPeriods()).hasSize(4);
    }

    @Test
    @DisplayName("each snapshot stores the period total as its own denominator")
    void snapshotStoresItsDenominator() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_jobs FROM skill_demand_snapshot WHERE period_start = '2026-06-01' LIMIT 1",
                Integer.class);
        assertThat(total).isEqualTo(10);
    }

    @Test
    @DisplayName("rebuilding is idempotent")
    void rebuildIsIdempotent() {
        int before = skillTrendRepository.countSnapshots();
        skillTrendRepository.rebuild();
        skillTrendRepository.rebuild();
        // The ETL re-runs, so this has to be safe to repeat.
        assertThat(skillTrendRepository.countSnapshots()).isEqualTo(before);
    }

    @Test
    @DisplayName("postings with no date are left out of the history")
    void undatedPostingsAreExcluded() {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, title, company_id, description, posted_date,
                                  source, source_url, content_fingerprint)
                VALUES (999, 'Undated', 1, 'Description', null, 'itest', 'https://x.invalid/999', ?)
                """, String.format("%064d", 999));
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (999, 1)");

        skillTrendRepository.rebuild();

        // A posting with no date belongs to no period and must not inflate one.
        Integer june = jdbcTemplate.queryForObject(
                "SELECT total_jobs FROM skill_demand_snapshot WHERE period_start = '2026-06-01' LIMIT 1",
                Integer.class);
        assertThat(june).isEqualTo(10);
        assertThat(skillTrendRepository.findPeriods()).hasSize(4);
    }

    @Test
    @DisplayName("a rising skill is identified with its change in percentage points")
    void identifiesRisingSkill() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends").param("direction", "RISING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends[0].skill").value("Kubernetes"))
                .andExpect(jsonPath("$.trends[0].earlierSharePercentage").value(15.0))
                .andExpect(jsonPath("$.trends[0].recentSharePercentage").value(55.0))
                .andExpect(jsonPath("$.trends[0].changeInPercentagePoints").value(40.0))
                .andExpect(jsonPath("$.trends[0].direction").value("RISING"));
    }

    @Test
    @DisplayName("a falling skill is identified with a negative change")
    void identifiesFallingSkill() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends").param("direction", "FALLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends[0].skill").value("Java"))
                .andExpect(jsonPath("$.trends[0].earlierSharePercentage").value(55.0))
                .andExpect(jsonPath("$.trends[0].recentSharePercentage").value(15.0))
                .andExpect(jsonPath("$.trends[0].changeInPercentagePoints").value(-40.0))
                .andExpect(jsonPath("$.trends[0].direction").value("FALLING"));
    }

    @Test
    @DisplayName("a skill holding steady is reported as stable, not as a tiny trend")
    void steadySkillIsStable() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends").param("direction", "STABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends[0].skill").value("Python"))
                .andExpect(jsonPath("$.trends[0].changeInPercentagePoints").value(0.0))
                .andExpect(jsonPath("$.trends[0].direction").value("STABLE"));
    }

    @Test
    @DisplayName("without a direction the biggest movers come first, either way")
    void biggestMoversFirst() throws Exception {
        // Kubernetes moved +40 points and Java -40, so both lead on magnitude and the
        // name breaks the tie. Python moved least and comes last regardless.
        mockMvc.perform(get("/api/analytics/skills/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends.length()").value(3))
                .andExpect(jsonPath("$.trends[0].skill").value("Java"))
                .andExpect(jsonPath("$.trends[0].changeInPercentagePoints").value(-40.0))
                .andExpect(jsonPath("$.trends[1].skill").value("Kubernetes"))
                .andExpect(jsonPath("$.trends[1].changeInPercentagePoints").value(40.0))
                .andExpect(jsonPath("$.trends[2].skill").value("Python"));
    }

    @Test
    @DisplayName("the window reports which periods formed each half")
    void windowDescribesItself() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends"))
                .andExpect(jsonPath("$.window.periods").value(4))
                .andExpect(jsonPath("$.window.fromPeriod").value("2026-06-01"))
                .andExpect(jsonPath("$.window.toPeriod").value("2026-09-01"))
                .andExpect(jsonPath("$.window.earlierPeriods.length()").value(2))
                .andExpect(jsonPath("$.window.recentPeriods[0]").value("2026-08-01"))
                .andExpect(jsonPath("$.window.totalJobsInWindow").value(40));
    }

    @Test
    @DisplayName("each trend carries the per-period series behind it")
    void trendCarriesItsSeries() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends").param("direction", "RISING"))
                .andExpect(jsonPath("$.trends[0].series.length()").value(4))
                .andExpect(jsonPath("$.trends[0].series[0].period").value("2026-06-01"))
                .andExpect(jsonPath("$.trends[0].series[0].jobCount").value(1))
                .andExpect(jsonPath("$.trends[0].series[0].totalJobs").value(10))
                .andExpect(jsonPath("$.trends[0].series[0].sharePercentage").value(10.0))
                .andExpect(jsonPath("$.trends[0].series[3].jobCount").value(6));
    }

    @Test
    @DisplayName("a shorter window compares only the recent periods")
    void shorterWindow() throws Exception {
        // Two months: August against September alone.
        mockMvc.perform(get("/api/analytics/skills/trends").param("months", "2"))
                .andExpect(jsonPath("$.window.periods").value(2))
                .andExpect(jsonPath("$.window.fromPeriod").value("2026-08-01"))
                .andExpect(jsonPath("$.window.totalJobsInWindow").value(20));
    }

    @Test
    @DisplayName("low volume skills are excluded, so noise is not read as a trend")
    void lowVolumeSkillsExcluded() throws Exception {
        // Kubernetes appears in 14 postings overall; a threshold above that drops it.
        mockMvc.perform(get("/api/analytics/skills/trends").param("minJobs", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.minJobsThreshold").value(15))
                .andExpect(jsonPath("$.trends[?(@.skill == 'Kubernetes')]").isEmpty());
    }

    @Test
    @DisplayName("too little history yields an empty result rather than a made up direction")
    void insufficientHistory() throws Exception {
        jdbcTemplate.execute("DELETE FROM skill_demand_snapshot WHERE period_start > '2026-06-01'");

        mockMvc.perform(get("/api/analytics/skills/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trends.length()").value(0))
                .andExpect(jsonPath("$.window.periods").value(1));
    }

    @Test
    @DisplayName("the window parameters are validated")
    void validatesParameters() throws Exception {
        mockMvc.perform(get("/api/analytics/skills/trends").param("months", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/analytics/skills/trends").param("direction", "SIDEWAYS"))
                .andExpect(status().isBadRequest());
    }

    private void link(long jobId, long skillId) {
        jdbcTemplate.update("INSERT INTO job_skills (job_id, skill_id) VALUES (?, ?)", jobId, skillId);
    }
}
