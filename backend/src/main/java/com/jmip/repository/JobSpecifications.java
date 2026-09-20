package com.jmip.repository;

import com.jmip.entity.Job;
import com.jmip.entity.Location;
import com.jmip.entity.Skill;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Builds the job search predicate from whichever filters the caller supplied.
 *
 * <p>Each filter is its own specification and they are combined with AND, so adding a
 * filter later means adding one method rather than editing a growing query string.
 */
public final class JobSpecifications {

    private JobSpecifications() {
    }

    /** Case-insensitive substring match on the posting's title. */
    public static Specification<Job> titleContains(String title) {
        return (root, query, builder) -> builder.like(
                builder.lower(root.get("title")), containsPattern(title));
    }

    public static Specification<Job> companyNameContains(String company) {
        return (root, query, builder) -> builder.like(
                builder.lower(root.get("company").get("name")), containsPattern(company));
    }

    /**
     * Matches the free-text location against city, state or country, because a caller
     * searching "India" and one searching "Pune" both expect results.
     */
    public static Specification<Job> locationMatches(String location) {
        return (root, query, builder) -> {
            Join<Job, Location> join = root.join("location", JoinType.INNER);
            String pattern = containsPattern(location);
            return builder.or(
                    builder.like(builder.lower(join.get("city")), pattern),
                    builder.like(builder.lower(join.get("state")), pattern),
                    builder.like(builder.lower(join.get("country")), pattern));
        };
    }

    /**
     * Matches on skill name, via EXISTS rather than a join.
     *
     * <p>A join to a collection multiplies rows, so it would need SELECT DISTINCT to stop
     * a job with three skills appearing three times. DISTINCT then collides with the
     * default ordering, because PostgreSQL requires every ORDER BY expression to appear
     * in the select list of a distinct query. EXISTS sidesteps both: one row per job, no
     * DISTINCT, and the planner can stop at the first match.
     */
    public static Specification<Job> hasSkill(String skill) {
        String wanted = skill.trim().toLowerCase(Locale.ROOT);
        return (root, query, builder) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<Job> subRoot = subquery.from(Job.class);
            Join<Job, Skill> skillJoin = subRoot.join("skills", JoinType.INNER);
            subquery.select(builder.literal(1))
                    .where(builder.and(
                            builder.equal(subRoot.get("id"), root.get("id")),
                            builder.equal(builder.lower(skillJoin.get("name")), wanted)));
            return builder.exists(subquery);
        };
    }

    /** Exact match: employment type is a fixed vocabulary, not free text. */
    public static Specification<Job> employmentTypeIs(String employmentType) {
        return (root, query, builder) -> builder.equal(
                root.get("employmentType"), employmentType.trim().toUpperCase(Locale.ROOT));
    }

    /** Matches everything, so callers can fold filters onto a neutral starting point. */
    public static Specification<Job> all() {
        return (root, query, builder) -> builder.conjunction();
    }

    /**
     * The default ordering: newest first, with undated postings last.
     *
     * <p>This cannot be expressed as a {@code Sort}, because Hibernate rejects null
     * precedence on criteria queries. PostgreSQL puts nulls first on a descending sort,
     * so a plain "newest first" would lead with the postings whose date is unknown — the
     * least useful rows in the most prominent position. Coalescing to a date far in the
     * past gets the same result through an expression the Criteria API does support.
     *
     * <p>Id breaks ties, so paging never shows the same row twice.
     */
    public static Specification<Job> newestFirst() {
        return (root, query, builder) -> {
            // The count query has no ORDER BY, and adding one would make it invalid SQL.
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(
                        builder.desc(builder.coalesce(root.get("postedDate"), LocalDate.of(1, 1, 1))),
                        builder.desc(root.get("id")));
            }
            return builder.conjunction();
        };
    }

    private static String containsPattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
