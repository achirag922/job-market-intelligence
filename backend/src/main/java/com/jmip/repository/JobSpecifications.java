package com.jmip.repository;

import com.jmip.entity.Company;
import com.jmip.entity.Job;
import com.jmip.entity.Location;
import com.jmip.entity.Skill;
import com.jmip.service.analytics.ExperienceBucket;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds the job search predicate from whichever filters the caller supplied.
 *
 * <p>Each filter is its own specification and they are combined with AND, so adding a
 * filter later means adding one method rather than editing a growing query string.
 */
public final class JobSpecifications {

    /** Escapes LIKE wildcards in user input, so a search for "50%" means fifty percent. */
    private static final char LIKE_ESCAPE = '\\';

    /** Dates far enough in the past or future to sort undated postings to the end. */
    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    /** Search words beyond this are ignored; a pasted paragraph is not a search. */
    private static final int MAX_QUERY_TERMS = 8;

    private JobSpecifications() {
    }

    // ------------------------------------------------------------------ text search

    /**
     * Free-text search across the fields a person would expect a job search to read.
     *
     * <p>Every word has to match, and each may match anywhere: "java spring" finds a
     * posting titled "Java Engineer" that lists Spring Boot, because "java" is in the title
     * and "spring" is in a skill. Requiring each word somewhere, rather than the phrase
     * verbatim, is what makes a two-word search useful — the words rarely sit together.
     *
     * <p>Location is a left join here: an inner join would drop every posting without a
     * location from every search, including searches that have nothing to do with place.
     */
    public static Specification<Job> matchesQuery(String q) {
        List<String> terms = terms(q);
        return (root, query, builder) -> {
            if (terms.isEmpty()) {
                return builder.conjunction();
            }
            Join<Job, Company> company = root.join("company", JoinType.INNER);
            Join<Job, Location> location = root.join("location", JoinType.LEFT);

            List<Predicate> perTerm = new ArrayList<>(terms.size());
            for (String term : terms) {
                String pattern = containsPattern(term);
                perTerm.add(builder.or(
                        like(builder, root.get("title"), pattern),
                        like(builder, company.get("name"), pattern),
                        like(builder, location.get("city"), pattern),
                        like(builder, location.get("state"), pattern),
                        like(builder, location.get("country"), pattern),
                        like(builder, root.get("description"), pattern),
                        builder.exists(skillLike(root, query, builder, pattern))));
            }
            return builder.and(perTerm.toArray(Predicate[]::new));
        };
    }

    /**
     * The search text split into words: trimmed, lower-cased, whitespace collapsed,
     * duplicates removed, capped.
     */
    public static List<String> terms(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return Arrays.stream(q.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(MAX_QUERY_TERMS)
                .toList();
    }

    /** Case-insensitive substring match on the posting's title. */
    public static Specification<Job> titleContains(String title) {
        return (root, query, builder) -> like(builder, root.get("title"), containsPattern(title));
    }

    public static Specification<Job> companyNameContains(String company) {
        return (root, query, builder) ->
                like(builder, root.get("company").get("name"), containsPattern(company));
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
                    like(builder, join.get("city"), pattern),
                    like(builder, join.get("state"), pattern),
                    like(builder, join.get("country"), pattern));
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

    // ---------------------------------------------------------------- exact filters

    /** Exact match: employment type is a fixed vocabulary, not free text. */
    public static Specification<Job> employmentTypeIs(String employmentType) {
        return (root, query, builder) -> builder.equal(
                root.get("employmentType"), employmentType.trim().toUpperCase(Locale.ROOT));
    }

    /** Exact match on the V4 job category, which is a controlled vocabulary. */
    public static Specification<Job> categoryIs(String category) {
        return (root, query, builder) -> builder.equal(
                builder.lower(root.get("jobCategory")), category.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Postings in one experience band, by their minimum requirement.
     *
     * <p>The band edges come from {@link ExperienceBucket}, the same definition the
     * experience distribution uses, so a filter labelled "2–5 years" selects exactly the
     * postings the chart counts under that label. Bands are half open: a posting asking
     * for exactly 5 years is in 5–8, not 2–5.
     */
    public static Specification<Job> experienceIn(ExperienceBucket bucket) {
        return (root, query, builder) -> {
            Expression<Short> minimum = root.get("experienceMin");
            if (bucket == ExperienceBucket.UNSPECIFIED) {
                return builder.isNull(minimum);
            }
            Predicate atLeast = builder.greaterThanOrEqualTo(minimum, bucket.minYears().shortValue());
            if (bucket.maxYearsExclusive() == null) {
                return atLeast;
            }
            return builder.and(atLeast,
                    builder.lessThan(minimum, bucket.maxYearsExclusive().shortValue()));
        };
    }

    /**
     * Postings whose stated pay overlaps the requested range, in one currency.
     *
     * <p>Overlap rather than containment, because both sides are ranges. A posting paying
     * 90–130k is a match for someone looking for at least 120k — the top of its band
     * reaches them — and requiring its minimum to clear 120k would hide it.
     *
     * <p>Postings that state no salary are excluded, never treated as zero. A missing
     * salary is unknown, and unknown does not satisfy "at least" anything.
     *
     * @param minimum the lowest pay wanted, or null for no floor
     * @param maximum the highest pay wanted, or null for no ceiling
     */
    public static Specification<Job> salaryOverlaps(String currency, BigDecimal minimum,
                                                    BigDecimal maximum) {
        String code = currency.trim().toUpperCase(Locale.ROOT);
        return (root, query, builder) -> {
            Expression<BigDecimal> low = root.get("salaryMin");
            // A posting stating only a minimum is taken at that figure. Reading it as
            // open-ended would let "40,000" satisfy a floor of a million.
            Expression<BigDecimal> high = builder.coalesce(root.get("salaryMax"), low);

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("currency"), code));
            predicates.add(builder.isNotNull(low));
            if (minimum != null) {
                predicates.add(builder.greaterThanOrEqualTo(high, minimum));
            }
            if (maximum != null) {
                predicates.add(builder.lessThanOrEqualTo(low, maximum));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Postings with a stated currency, for ordering by salary within it. */
    public static Specification<Job> currencyIs(String currency) {
        String code = currency.trim().toUpperCase(Locale.ROOT);
        return (root, query, builder) -> builder.equal(root.get("currency"), code);
    }

    /**
     * Whether the posting names a place.
     *
     * <p>Not a remote filter, and not labelled as one. The ETL maps "remote", "work from
     * home" and "unspecified" all to no location, so the dataset cannot distinguish a
     * remote role from one that simply did not say — and a filter claiming to would be
     * wrong about exactly the postings it was asked to find.
     */
    public static Specification<Job> locationStated(boolean stated) {
        return (root, query, builder) -> stated
                ? builder.isNotNull(root.get("location"))
                : builder.isNull(root.get("location"));
    }

    /** Matches everything, so callers can fold filters onto a neutral starting point. */
    public static Specification<Job> all() {
        return (root, query, builder) -> builder.conjunction();
    }

    // -------------------------------------------------------------------- orderings

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
        return ordered((root, builder) -> List.of(
                builder.desc(builder.coalesce(root.get("postedDate"), EARLIEST)),
                builder.desc(root.get("id"))));
    }

    /** Oldest first, with undated postings still last rather than suddenly first. */
    public static Specification<Job> oldestFirst() {
        return ordered((root, builder) -> List.of(
                builder.asc(builder.coalesce(root.get("postedDate"), LATEST)),
                builder.asc(root.get("id"))));
    }

    /**
     * Highest or lowest pay first, postings without a stated salary last either way.
     *
     * <p>Ranked on the top of the band for "highest" and the bottom for "lowest", which is
     * what each question is asking. Only meaningful within one currency; the service
     * refuses these orderings unless a currency is filtered.
     */
    public static Specification<Job> bySalary(boolean highestFirst) {
        return ordered((root, builder) -> {
            Expression<BigDecimal> low = root.get("salaryMin");
            Expression<BigDecimal> high = builder.coalesce(root.get("salaryMax"), low);
            // An impossible value in the "worst" direction pushes unsalaried rows to the end.
            return highestFirst
                    ? List.of(builder.desc(builder.coalesce(high, BigDecimal.valueOf(-1))),
                              builder.desc(root.get("id")))
                    : List.of(builder.asc(builder.coalesce(low, new BigDecimal("1e15"))),
                              builder.asc(root.get("id")));
        });
    }

    /** Alphabetical by title, case-insensitive so "backend" and "Backend" sit together. */
    public static Specification<Job> byTitle() {
        return ordered((root, builder) -> List.of(
                builder.asc(builder.lower(root.get("title"))),
                builder.asc(root.get("id"))));
    }

    /** Alphabetical by company, then newest first within each company. */
    public static Specification<Job> byCompany() {
        return ordered((root, builder) -> List.of(
                builder.asc(builder.lower(root.get("company").get("name"))),
                builder.desc(builder.coalesce(root.get("postedDate"), EARLIEST)),
                builder.asc(root.get("id"))));
    }

    /**
     * Best match first, for a search.
     *
     * <p>A deterministic score, not a ranking model: for each search word, 3 points if it
     * is in the title, 2 if it is in the company name, 1 if it is in the description.
     * Summed over the words, highest first, newest first within a score.
     *
     * <p>The weights say what a job-seeker usually means: a word in the title is about the
     * role, a word only in the description may be a passing mention. Skills filter but do
     * not score — a skill match is already required for a skill word to count at all, and
     * scoring it would need a subquery in ORDER BY that the database would run per row.
     */
    public static Specification<Job> byRelevance(String q) {
        List<String> terms = terms(q);
        return ordered((root, builder) -> {
            Expression<Integer> score = builder.literal(0);
            for (String term : terms) {
                String pattern = containsPattern(term);
                score = builder.sum(score, points(builder, like(builder, root.get("title"), pattern), 3));
                score = builder.sum(score,
                        points(builder, like(builder, root.get("company").get("name"), pattern), 2));
                score = builder.sum(score,
                        points(builder, like(builder, root.get("description"), pattern), 1));
            }
            return List.of(
                    builder.desc(score),
                    builder.desc(builder.coalesce(root.get("postedDate"), EARLIEST)),
                    builder.desc(root.get("id")));
        });
    }

    // ---------------------------------------------------------------------- helpers

    /** Something that can build an ORDER BY from the query root. */
    @FunctionalInterface
    private interface OrderBuilder {
        List<jakarta.persistence.criteria.Order> build(Root<Job> root, CriteriaBuilder builder);
    }

    /**
     * Attaches an ordering to the query without adding a predicate.
     *
     * <p>Skipped for the count query: it has no ORDER BY, and adding one would make it
     * invalid SQL.
     */
    private static Specification<Job> ordered(OrderBuilder orders) {
        return (root, query, builder) -> {
            if (query != null && !Long.class.equals(query.getResultType())) {
                query.orderBy(orders.build(root, builder));
            }
            return builder.conjunction();
        };
    }

    private static Expression<Integer> points(CriteriaBuilder builder, Predicate matched, int value) {
        return builder.<Integer>selectCase().when(matched, value).otherwise(0);
    }

    private static Subquery<Integer> skillLike(Root<Job> root,
                                               jakarta.persistence.criteria.CommonAbstractCriteria query,
                                               CriteriaBuilder builder, String pattern) {
        Subquery<Integer> subquery = query.subquery(Integer.class);
        Root<Job> subRoot = subquery.from(Job.class);
        Join<Job, Skill> skillJoin = subRoot.join("skills", JoinType.INNER);
        subquery.select(builder.literal(1))
                .where(builder.and(
                        builder.equal(subRoot.get("id"), root.get("id")),
                        like(builder, skillJoin.get("name"), pattern)));
        return subquery;
    }

    private static Predicate like(CriteriaBuilder builder, Expression<String> field, String pattern) {
        return builder.like(builder.lower(field), pattern, LIKE_ESCAPE);
    }

    /**
     * A contains-pattern with the user's own wildcards escaped.
     *
     * <p>The value is always a bound parameter, so this is not about injection. It is
     * about meaning: an unescaped "%" matches everything, and "c_d" would match "cad".
     */
    private static String containsPattern(String value) {
        String escaped = value.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
