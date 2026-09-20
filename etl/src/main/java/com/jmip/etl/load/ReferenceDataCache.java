package com.jmip.etl.load;

import com.jmip.etl.model.TransformedJob;
import com.jmip.etl.transform.SkillExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves company, location and skill names to their surrogate keys.
 *
 * <p>This is what keeps the writer free of N+1 queries. Rather than a lookup per job, the
 * whole of each reference table is read once and then maintained in memory; a chunk costs
 * at most one insert and one select per table, and only when it introduces names that
 * have not been seen before. The three tables are small by construction — thousands of
 * companies against potentially millions of postings — so holding them is cheap.
 *
 * <p>Not thread safe, which is fine: the ingestion step is single threaded. Making the
 * step multi threaded would mean revisiting this class first.
 */
@Component
public class ReferenceDataCache {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataCache.class);

    private final JdbcTemplate jdbcTemplate;
    private final SkillExtractor skillExtractor;

    private final Map<String, Long> companyIds = new HashMap<>();
    private final Map<String, Long> locationIds = new HashMap<>();
    private final Map<String, Long> skillIds = new HashMap<>();
    private boolean primed;

    public ReferenceDataCache(JdbcTemplate jdbcTemplate, SkillExtractor skillExtractor) {
        this.jdbcTemplate = jdbcTemplate;
        this.skillExtractor = skillExtractor;
    }

    /** Loads the existing reference rows. Called once at the start of the step. */
    public void prime() {
        companyIds.clear();
        locationIds.clear();
        skillIds.clear();

        jdbcTemplate.query("SELECT id, name FROM companies",
                (RowCallbackHandler) rs -> companyIds.put(key(rs.getString("name")), rs.getLong("id")));
        jdbcTemplate.query("SELECT id, city, state, country FROM locations",
                (RowCallbackHandler) rs -> locationIds.put(
                        locationKey(rs.getString("city"), rs.getString("state"), rs.getString("country")),
                        rs.getLong("id")));
        jdbcTemplate.query("SELECT id, name FROM skills",
                (RowCallbackHandler) rs -> skillIds.put(key(rs.getString("name")), rs.getLong("id")));

        primed = true;
        log.info("Reference cache primed: {} companies, {} locations, {} skills",
                companyIds.size(), locationIds.size(), skillIds.size());
    }

    /** Creates any company, location or skill in this chunk that does not exist yet. */
    public void ensureFor(Collection<TransformedJob> jobs) {
        if (!primed) {
            prime();
        }
        ensureCompanies(jobs);
        ensureLocations(jobs);
        ensureSkills(jobs);
    }

    public Long companyId(TransformedJob job) {
        return companyIds.get(key(job.companyName()));
    }

    public Long locationId(TransformedJob job) {
        if (!job.hasLocation()) {
            return null;
        }
        return locationIds.get(locationKey(job.city(), job.state(), job.country()));
    }

    public Long skillId(String skillName) {
        return skillIds.get(key(skillName));
    }

    private void ensureCompanies(Collection<TransformedJob> jobs) {
        List<TransformedJob> missing = jobs.stream()
                .filter(job -> job.companyName() != null && !companyIds.containsKey(key(job.companyName())))
                .collect(java.util.stream.Collectors.toMap(
                        job -> key(job.companyName()), job -> job, (first, second) -> first, java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (missing.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO companies (name, industry, website) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                missing, missing.size(), (PreparedStatement ps, TransformedJob job) -> {
                    ps.setString(1, job.companyName());
                    ps.setString(2, job.companyIndustry());
                    ps.setString(3, job.companyWebsite());
                });

        List<String> keys = missing.stream().map(job -> key(job.companyName())).toList();
        jdbcTemplate.query(
                "SELECT id, name FROM companies WHERE lower(name) IN (" + placeholders(keys.size()) + ")",
                (RowCallbackHandler) rs -> companyIds.put(key(rs.getString("name")), rs.getLong("id")),
                keys.toArray());
        log.debug("Created {} companies", missing.size());
    }

    private void ensureLocations(Collection<TransformedJob> jobs) {
        List<TransformedJob> missing = jobs.stream()
                .filter(TransformedJob::hasLocation)
                .filter(job -> !locationIds.containsKey(locationKey(job.city(), job.state(), job.country())))
                .collect(java.util.stream.Collectors.toMap(
                        job -> locationKey(job.city(), job.state(), job.country()),
                        job -> job, (first, second) -> first, java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (missing.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(
                "INSERT INTO locations (city, state, country) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                missing, missing.size(), (PreparedStatement ps, TransformedJob job) -> {
                    ps.setString(1, job.city());
                    ps.setString(2, job.state());
                    ps.setString(3, job.country());
                });

        // IS NOT DISTINCT FROM rather than =, because city and state are frequently null
        // and equality against null never matches.
        StringBuilder sql = new StringBuilder(
                "SELECT l.id, l.city, l.state, l.country FROM locations l JOIN (VALUES ");
        for (int i = 0; i < missing.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append("(?::text, ?::text, ?::text)");
        }
        sql.append(") AS v(city, state, country) ON lower(l.country) IS NOT DISTINCT FROM lower(v.country)")
           .append(" AND lower(l.state) IS NOT DISTINCT FROM lower(v.state)")
           .append(" AND lower(l.city) IS NOT DISTINCT FROM lower(v.city)");

        Object[] params = new Object[missing.size() * 3];
        for (int i = 0; i < missing.size(); i++) {
            TransformedJob job = missing.get(i);
            params[i * 3] = job.city();
            params[i * 3 + 1] = job.state();
            params[i * 3 + 2] = job.country();
        }
        jdbcTemplate.query(sql.toString(),
                (RowCallbackHandler) rs -> locationIds.put(
                        locationKey(rs.getString("city"), rs.getString("state"), rs.getString("country")),
                        rs.getLong("id")),
                params);
        log.debug("Created {} locations", missing.size());
    }

    private void ensureSkills(Collection<TransformedJob> jobs) {
        Set<String> names = new LinkedHashSet<>();
        for (TransformedJob job : jobs) {
            names.addAll(job.skills());
        }
        ensureSkillsByName(names);
    }

    /**
     * Creates any of these skills that does not exist yet.
     *
     * <p>Exposed by name as well as by job so that reprocessing, which works from stored
     * rows rather than from raw records, resolves skills through this same cache instead
     * of growing a second copy of the logic.
     */
    public void ensureSkillsByName(Collection<String> skillNames) {
        if (!primed) {
            prime();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String skill : skillNames) {
            if (skill != null && !skillIds.containsKey(key(skill))) {
                missing.add(skill);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        List<String> names = List.copyOf(missing);
        jdbcTemplate.batchUpdate(
                "INSERT INTO skills (name, category) VALUES (?, ?) ON CONFLICT DO NOTHING",
                names, names.size(), (PreparedStatement ps, String name) -> {
                    ps.setString(1, name);
                    ps.setString(2, skillExtractor.categoryOf(name));
                });

        List<String> keys = names.stream().map(ReferenceDataCache::key).toList();
        jdbcTemplate.query(
                "SELECT id, name FROM skills WHERE lower(name) IN (" + placeholders(keys.size()) + ")",
                (RowCallbackHandler) rs -> skillIds.put(key(rs.getString("name")), rs.getLong("id")),
                keys.toArray());
        log.debug("Created {} skills", names.size());
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Country first, mirroring the unique index, so the key and the constraint agree. */
    private static String locationKey(String city, String state, String country) {
        return key(country) + "|" + key(state) + "|" + key(city);
    }
}
