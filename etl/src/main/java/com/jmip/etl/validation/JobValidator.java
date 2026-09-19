package com.jmip.etl.validation;

import com.jmip.etl.model.TransformedJob;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Last gate before the database.
 *
 * <p>Every rule here mirrors a constraint the schema enforces anyway. Checking in the
 * pipeline as well is what turns a whole failed chunk into one explained, recorded
 * rejection: a constraint violation at write time would roll back the other 99 good
 * records in the same chunk and say very little about why.
 */
@Component
public class JobValidator {

    private static final Set<String> VALID_EMPLOYMENT_TYPES = Set.of(
            "FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP", "TEMPORARY", "FREELANCE");

    private static final LocalDate EARLIEST_PLAUSIBLE_POSTING = LocalDate.of(1990, 1, 1);
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_COMPANY_LENGTH = 255;

    private final Clock clock;

    public JobValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return every reason the record cannot be loaded; empty when it is good
     */
    public List<String> validate(TransformedJob job) {
        List<String> reasons = new ArrayList<>();

        if (isBlank(job.title())) {
            reasons.add("missing job title");
        } else if (job.title().length() > MAX_TITLE_LENGTH) {
            reasons.add("job title exceeds " + MAX_TITLE_LENGTH + " characters");
        }

        if (isBlank(job.companyName())) {
            reasons.add("missing company");
        } else if (job.companyName().length() > MAX_COMPANY_LENGTH) {
            reasons.add("company name exceeds " + MAX_COMPANY_LENGTH + " characters");
        }

        if (isBlank(job.description())) {
            reasons.add("missing description");
        }

        if (isBlank(job.source())) {
            reasons.add("missing source");
        }

        validateDate(job, reasons);
        validateExperience(job, reasons);
        validateSalary(job, reasons);
        validateLocation(job, reasons);

        if (job.employmentType() != null && !VALID_EMPLOYMENT_TYPES.contains(job.employmentType())) {
            reasons.add("invalid employment type: " + job.employmentType());
        }

        return reasons;
    }

    private void validateDate(TransformedJob job, List<String> reasons) {
        LocalDate posted = job.postedDate();
        if (posted == null) {
            return;
        }
        if (posted.isAfter(LocalDate.now(clock))) {
            reasons.add("posted date is in the future: " + posted);
        } else if (posted.isBefore(EARLIEST_PLAUSIBLE_POSTING)) {
            reasons.add("posted date is implausibly old: " + posted);
        }
    }

    private void validateExperience(TransformedJob job, List<String> reasons) {
        Integer min = job.experienceMin();
        Integer max = job.experienceMax();
        if (min != null && min < 0) {
            reasons.add("negative minimum experience: " + min);
        }
        if (max != null && max < 0) {
            reasons.add("negative maximum experience: " + max);
        }
        if (min != null && max != null && max < min) {
            reasons.add("experience range is inverted: " + min + " to " + max);
        }
    }

    private void validateSalary(TransformedJob job, List<String> reasons) {
        var min = job.salaryMin();
        var max = job.salaryMax();
        if (min != null && min.signum() < 0) {
            reasons.add("negative minimum salary: " + min);
        }
        if (max != null && max.signum() < 0) {
            reasons.add("negative maximum salary: " + max);
        }
        if (min != null && max != null && max.compareTo(min) < 0) {
            reasons.add("salary range is inverted: " + min + " to " + max);
        }
        boolean hasAmount = min != null || max != null;
        if (hasAmount && isBlank(job.currency())) {
            reasons.add("salary given without a currency");
        }
        if (job.currency() != null && !job.currency().matches("^[A-Z]{3}$")) {
            reasons.add("currency is not a three letter ISO 4217 code: " + job.currency());
        }
    }

    private void validateLocation(TransformedJob job, List<String> reasons) {
        // No location at all is legitimate: remote postings have none. A city without a
        // country is not, because the locations table requires a country.
        if (job.country() == null && (job.city() != null || job.state() != null)) {
            reasons.add("location has a city or state but no country");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
