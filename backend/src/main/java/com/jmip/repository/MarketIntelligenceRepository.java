package com.jmip.repository;

import com.jmip.service.analytics.ExperienceBucket;
import com.jmip.service.market.MarketFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * V7.5: market aggregates over one filtered set of postings. Read-only SQL over the tables the
 * ETL already fills; nothing is stored. Every query starts from the same filtered set, so the
 * totals, shares and series of one response always describe the same postings.
 *
 * <p>Month series group by {@code posted_date}, as the V6.1 skill-demand snapshot does.
 * Postings without a date count in totals but in no month.
 */
@Repository
public class MarketIntelligenceRepository {

    /** Work mode, from what the posting says in words; the data has no field for it. */
    static final String WORK_MODE = "CASE WHEN j.description ~* '\\mhybrid\\M' THEN 'HYBRID'"
            + " WHEN j.description ~* '\\mremote\\M' THEN 'REMOTE'"
            + " WHEN j.description ~* '\\m(on-?site|in[- ]office)\\M' THEN 'ON_SITE'"
            + " ELSE 'NOT_STATED' END";

    private static final String FILTERED = """
            WITH filtered AS (
                SELECT j.id, j.company_id, j.location_id, j.salary_min, j.salary_max, trim(j.currency) AS currency,
                       j.job_category, date_trunc('month', j.posted_date)::date AS month, %s AS work_mode
                FROM jobs j
                LEFT JOIN locations l ON l.id = j.location_id
                WHERE %s
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public MarketIntelligenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Window(long postings, long datedPostings, LocalDate earliest, LocalDate latest) {
    }

    public record SalaryRow(String currency, String category, LocalDate month, long postings,
                            BigDecimal averageMin, BigDecimal averageMax, BigDecimal lowestMin, BigDecimal highestMax) {
    }

    /** One count: a location, work mode, company or skill, optionally in one month. */
    public record CountRow(Long id, String name, String extra, LocalDate month, long postings) {
    }

    /** The newest posting date in the whole dataset, the anchor for "the last N months". */
    public LocalDate latestPostedDate() {
        return jdbcTemplate.queryForObject("SELECT max(posted_date) FROM jobs", LocalDate.class);
    }

    public Window window(MarketFilter filter) {
        Query query = query(filter, """
                SELECT count(*) AS postings, count(month) AS dated, min(month) AS earliest, max(month) AS latest
                FROM filtered
                """);
        return jdbcTemplate.queryForObject(query.sql(), (rs, row) -> new Window(rs.getLong("postings"), rs.getLong("dated"),
                rs.getObject("earliest", LocalDate.class), rs.getObject("latest", LocalDate.class)), query.args());
    }

    /** Stated salaries per currency, never pooled across currencies: overall, per category, or per month. */
    public List<SalaryRow> salaries(MarketFilter filter, boolean byCategory, boolean byMonth) {
        String groups = "currency" + (byCategory ? ", job_category" : "") + (byMonth ? ", month" : "");
        Query query = query(filter, """
                SELECT currency, %s AS category, %s AS month, count(*) AS postings,
                       avg(salary_min) AS avg_min, avg(salary_max) AS avg_max, min(salary_min) AS low, max(salary_max) AS high
                FROM filtered
                WHERE salary_min IS NOT NULL AND currency IS NOT NULL %s
                GROUP BY %s
                ORDER BY %s count(*) DESC, currency
                """.formatted(byCategory ? "job_category" : "NULL", byMonth ? "month" : "NULL::date",
                byMonth ? "AND month IS NOT NULL" : "", groups, byMonth ? "month," : ""));
        return jdbcTemplate.query(query.sql(), (rs, row) -> new SalaryRow(rs.getString("currency"), rs.getString("category"),
                rs.getObject("month", LocalDate.class), rs.getLong("postings"), rs.getBigDecimal("avg_min"),
                rs.getBigDecimal("avg_max"), rs.getBigDecimal("low"), rs.getBigDecimal("high")), query.args());
    }

    /** Postings per location (name = display name, extra = country); a null id is "no location stated". */
    public List<CountRow> locations(MarketFilter filter) {
        Query query = query(filter, """
                SELECT f.location_id AS id,
                       concat_ws(', ', nullif(l.city, ''), nullif(l.state, ''), l.country) AS name,
                       l.country AS extra, NULL::date AS month, count(*) AS postings
                FROM filtered f LEFT JOIN locations l ON l.id = f.location_id
                GROUP BY f.location_id, l.city, l.state, l.country
                ORDER BY count(*) DESC, name
                """);
        return jdbcTemplate.query(query.sql(), MarketIntelligenceRepository::countRow, query.args());
    }

    /** Postings per work mode (name), overall or per month. */
    public List<CountRow> workModes(MarketFilter filter, boolean byMonth) {
        Query query = query(filter, """
                SELECT NULL::bigint AS id, work_mode AS name, NULL AS extra, %s AS month, count(*) AS postings
                FROM filtered %s
                GROUP BY work_mode %s
                ORDER BY %s count(*) DESC
                """.formatted(byMonth ? "month" : "NULL::date", byMonth ? "WHERE month IS NOT NULL" : "",
                byMonth ? ", month" : "", byMonth ? "month," : ""));
        return jdbcTemplate.query(query.sql(), MarketIntelligenceRepository::countRow, query.args());
    }

    /** The companies with the most postings (extra = industry). */
    public List<CountRow> companies(MarketFilter filter, int limit) {
        Query query = query(filter, """
                SELECT c.id, c.name, c.industry AS extra, NULL::date AS month, count(*) AS postings
                FROM filtered f JOIN companies c ON c.id = f.company_id
                GROUP BY c.id, c.name, c.industry
                ORDER BY count(*) DESC, c.name
                LIMIT %d
                """.formatted(limit));
        return jdbcTemplate.query(query.sql(), MarketIntelligenceRepository::countRow, query.args());
    }

    /** Postings per month for the given companies. */
    public List<CountRow> companiesByMonth(MarketFilter filter, List<Long> companyIds) {
        if (companyIds.isEmpty()) {
            return List.of();
        }
        Query query = query(filter, """
                SELECT c.id, c.name, NULL AS extra, f.month, count(*) AS postings
                FROM filtered f JOIN companies c ON c.id = f.company_id
                WHERE f.month IS NOT NULL AND c.id IN (%s)
                GROUP BY c.id, c.name, f.month
                ORDER BY f.month, c.name
                """.formatted(String.join(",", companyIds.stream().map(id -> "?").toList())));
        List<Object> args = new ArrayList<>(List.of(query.args()));
        args.addAll(companyIds);
        return jdbcTemplate.query(query.sql(), MarketIntelligenceRepository::countRow, args.toArray());
    }

    /** The most requested skills among the filtered postings (extra = skill category). */
    public List<CountRow> skills(MarketFilter filter, int limit) {
        Query query = query(filter, """
                SELECT s.id, s.name, s.category AS extra, NULL::date AS month, count(*) AS postings
                FROM filtered f JOIN job_skills js ON js.job_id = f.id JOIN skills s ON s.id = js.skill_id
                GROUP BY s.id, s.name, s.category
                ORDER BY count(*) DESC, s.name
                LIMIT %d
                """.formatted(limit));
        return jdbcTemplate.query(query.sql(), MarketIntelligenceRepository::countRow, query.args());
    }

    // ------------------------------------------------------------------ filter

    private record Query(String sql, Object[] args) {
    }

    private static CountRow countRow(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        Long boxed = rs.wasNull() ? null : id;
        return new CountRow(boxed, rs.getString("name"), rs.getString("extra"),
                rs.getObject("month", LocalDate.class), rs.getLong("postings"));
    }

    /** The shared WHERE, with the same meaning as the job search's filters; values are always bound. */
    private static Query query(MarketFilter filter, String select) {
        List<String> where = new ArrayList<>(List.of("TRUE"));
        List<Object> args = new ArrayList<>();
        if (filter.category() != null) {
            where.add("lower(j.job_category) = lower(?)");
            args.add(filter.category());
        }
        if (filter.location() != null) {
            String pattern = "%" + filter.location().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.add("(l.city ILIKE ? OR l.state ILIKE ? OR l.country ILIKE ?)");
            args.addAll(List.of(pattern, pattern, pattern));
        }
        ExperienceBucket band = filter.experience();
        if (band == ExperienceBucket.UNSPECIFIED) {
            where.add("j.experience_min IS NULL");
        } else if (band != null) {
            where.add("j.experience_min >= ?");
            args.add(band.minYears());
            if (band.maxYearsExclusive() != null) {
                where.add("j.experience_min < ?");
                args.add(band.maxYearsExclusive());
            }
        }
        if (filter.from() != null) {
            where.add("j.posted_date >= ?");
            args.add(filter.from());
        }
        String sql = FILTERED.formatted(WORK_MODE, String.join(" AND ", where)) + select;
        return new Query(sql, args.toArray());
    }
}
