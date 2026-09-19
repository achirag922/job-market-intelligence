package com.jmip.etl.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * A posting after cleaning, parsing and skill extraction: everything the writer needs,
 * shaped the way the database expects it.
 *
 * <p>Still not a JPA entity. The writer uses JDBC batches, and keeping this a plain
 * record means the pipeline never accidentally acquires persistence-context behaviour.
 *
 * @param skills canonical skill names; the writer resolves them to {@code skills.id}
 */
public record TransformedJob(
        String title,
        String companyName,
        String companyIndustry,
        String companyWebsite,
        String city,
        String state,
        String country,
        String description,
        String employmentType,
        Integer experienceMin,
        Integer experienceMax,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        LocalDate postedDate,
        String source,
        String sourceUrl,
        String contentFingerprint,
        Set<String> skills) {

    public boolean hasLocation() {
        return country != null;
    }
}
