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
 * @param skills         canonical skill names; the writer resolves them to {@code skills.id}
 * @param classification which kind of role this is, with the evidence behind it
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
        Set<String> skills,
        JobClassification classification,
        // V8.1: the source's own id for the posting, when it gives one.
        String sourceJobId,
        // V8.2: when the source says it closes, and whether the source marked it closed.
        LocalDate expiresAt,
        boolean closed) {

    /** A posting whose source gives no id of its own. */
    public TransformedJob(String title, String companyName, String companyIndustry, String companyWebsite, String city,
                          String state, String country, String description, String employmentType, Integer experienceMin,
                          Integer experienceMax, BigDecimal salaryMin, BigDecimal salaryMax, String currency,
                          LocalDate postedDate, String source, String sourceUrl, String contentFingerprint,
                          Set<String> skills, JobClassification classification) {
        this(title, companyName, companyIndustry, companyWebsite, city, state, country, description, employmentType,
                experienceMin, experienceMax, salaryMin, salaryMax, currency, postedDate, source, sourceUrl,
                contentFingerprint, skills, classification, null, null, false);
    }

    public boolean hasLocation() {
        return country != null;
    }
}
