package com.jmip.etl.raw;

/**
 * One job posting exactly as it was read from the input file, before any cleaning.
 *
 * <p>Every field is a raw string, including the ones that end up structured in the
 * database. That is deliberate: real datasets publish experience as {@code "3-5 years"},
 * salary as {@code "$120,000 - $150,000"} and location as {@code "Austin, Texas, United
 * States"}, so the raw contract has to be text. Readers for structured formats compose
 * these strings, which keeps a single parsing path no matter what the input format is.
 *
 * <p>This type is intentionally separate from the database entities. Nothing here is
 * validated, normalised or trusted.
 */
public record RawJobRecord(
        String title,
        String company,
        String companyIndustry,
        String companyWebsite,
        String location,
        String description,
        String employmentType,
        String experience,
        String salary,
        String postedDate,
        String source,
        String sourceUrl) {
}
