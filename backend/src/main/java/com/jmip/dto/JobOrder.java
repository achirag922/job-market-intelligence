package com.jmip.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * The named orderings job search offers.
 *
 * <p>Separate from Spring's {@code sort} parameter, which keeps working exactly as before
 * for the plain fields. These exist because the useful orderings are not plain fields:
 *
 * <ul>
 *   <li><b>Newest and oldest</b> must put undated postings last. PostgreSQL puts nulls
 *       first on a descending sort, and Hibernate rejects null precedence on criteria
 *       queries, so a {@code Sort} cannot say this.</li>
 *   <li><b>Salary</b> has the same null problem, and a second one: it is only comparable
 *       within one currency. These orderings are refused unless a currency is filtered.</li>
 *   <li><b>Relevance</b> is a score computed from the search text, not a column.</li>
 * </ul>
 */
public enum JobOrder {

    NEWEST,
    OLDEST,
    /** Needs search text; without it every posting scores the same and this is NEWEST. */
    RELEVANCE,
    /** Needs a currency filter, because salaries in different currencies do not compare. */
    SALARY_HIGH,
    /** Needs a currency filter, for the same reason. */
    SALARY_LOW,
    TITLE,
    COMPANY;

    /** The URL value, lower case with hyphens: {@code salary-high}. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** @return the ordering a slug names, or empty for an unknown one */
    public static Optional<JobOrder> fromSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String normalised = slug.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (JobOrder order : values()) {
            if (order.name().equals(normalised)) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }

    public boolean needsCurrency() {
        return this == SALARY_HIGH || this == SALARY_LOW;
    }
}
