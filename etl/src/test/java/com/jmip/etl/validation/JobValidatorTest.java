package com.jmip.etl.validation;

import com.jmip.etl.model.TransformedJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JobValidatorTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);

    private final JobValidator validator = new JobValidator(FIXED_CLOCK);

    @Test
    @DisplayName("a complete record passes")
    void completeRecordPasses() {
        assertThat(validator.validate(valid().build())).isEmpty();
    }

    @Test
    @DisplayName("a minimal record with only the mandatory fields passes")
    void minimalRecordPasses() {
        TransformedJob job = valid()
                .city(null).state(null).country(null)
                .employmentType(null)
                .experienceMin(null).experienceMax(null)
                .salaryMin(null).salaryMax(null).currency(null)
                .postedDate(null)
                .build();
        assertThat(validator.validate(job)).isEmpty();
    }

    @Test
    @DisplayName("missing job title is reported")
    void missingTitle() {
        assertThat(validator.validate(valid().title(null).build())).contains("missing job title");
        assertThat(validator.validate(valid().title("   ").build())).contains("missing job title");
    }

    @Test
    @DisplayName("missing company is reported")
    void missingCompany() {
        assertThat(validator.validate(valid().companyName(null).build())).contains("missing company");
    }

    @Test
    @DisplayName("missing description is reported")
    void missingDescription() {
        assertThat(validator.validate(valid().description(null).build())).contains("missing description");
    }

    @Test
    @DisplayName("a posted date in the future is reported")
    void futureDate() {
        TransformedJob job = valid().postedDate(LocalDate.of(2026, 9, 20)).build();
        assertThat(validator.validate(job)).anyMatch(reason -> reason.startsWith("posted date is in the future"));
    }

    @Test
    @DisplayName("today's date is accepted")
    void todayIsAccepted() {
        assertThat(validator.validate(valid().postedDate(LocalDate.of(2026, 9, 19)).build())).isEmpty();
    }

    @Test
    @DisplayName("an implausibly old date is reported")
    void implausiblyOldDate() {
        TransformedJob job = valid().postedDate(LocalDate.of(1970, 1, 1)).build();
        assertThat(validator.validate(job)).anyMatch(reason -> reason.contains("implausibly old"));
    }

    @Test
    @DisplayName("an inverted salary range is reported")
    void invertedSalary() {
        TransformedJob job = valid()
                .salaryMin(new BigDecimal("150000")).salaryMax(new BigDecimal("120000")).build();
        assertThat(validator.validate(job)).anyMatch(reason -> reason.contains("salary range is inverted"));
    }

    @Test
    @DisplayName("a salary with no currency is reported")
    void salaryWithoutCurrency() {
        TransformedJob job = valid().currency(null).build();
        assertThat(validator.validate(job)).contains("salary given without a currency");
    }

    @Test
    @DisplayName("a malformed currency code is reported")
    void malformedCurrency() {
        assertThat(validator.validate(valid().currency("usd").build()))
                .anyMatch(reason -> reason.contains("ISO 4217"));
    }

    @Test
    @DisplayName("an inverted experience range is reported")
    void invertedExperience() {
        TransformedJob job = valid().experienceMin(8).experienceMax(3).build();
        assertThat(validator.validate(job)).anyMatch(reason -> reason.contains("experience range is inverted"));
    }

    @Test
    @DisplayName("negative experience is reported")
    void negativeExperience() {
        assertThat(validator.validate(valid().experienceMin(-1).experienceMax(null).build()))
                .anyMatch(reason -> reason.contains("negative minimum experience"));
    }

    @Test
    @DisplayName("a city with no country is reported")
    void cityWithoutCountry() {
        TransformedJob job = valid().city("Berlin").state(null).country(null).build();
        assertThat(validator.validate(job)).contains("location has a city or state but no country");
    }

    @Test
    @DisplayName("no location at all is valid, because remote postings have none")
    void noLocationIsValid() {
        TransformedJob job = valid().city(null).state(null).country(null).build();
        assertThat(validator.validate(job)).isEmpty();
    }

    @Test
    @DisplayName("an employment type outside the vocabulary is reported")
    void invalidEmploymentType() {
        assertThat(validator.validate(valid().employmentType("GIG").build()))
                .anyMatch(reason -> reason.contains("invalid employment type"));
    }

    @Test
    @DisplayName("every problem is reported, not just the first")
    void reportsEveryProblem() {
        TransformedJob job = valid().title(null).companyName(null).description(null).build();
        assertThat(validator.validate(job))
                .contains("missing job title", "missing company", "missing description");
    }

    private static Builder valid() {
        return new Builder();
    }

    /** Small builder so each test states only the field it is about. */
    private static final class Builder {
        private String title = "Backend Engineer";
        private String companyName = "Acme";
        private String description = "We need someone to build services.";
        private String city = "Berlin";
        private String state = null;
        private String country = "Germany";
        private String employmentType = "FULL_TIME";
        private Integer experienceMin = 3;
        private Integer experienceMax = 6;
        private BigDecimal salaryMin = new BigDecimal("70000");
        private BigDecimal salaryMax = new BigDecimal("95000");
        private String currency = "EUR";
        private LocalDate postedDate = LocalDate.of(2026, 8, 1);

        Builder title(String value) { this.title = value; return this; }
        Builder companyName(String value) { this.companyName = value; return this; }
        Builder description(String value) { this.description = value; return this; }
        Builder city(String value) { this.city = value; return this; }
        Builder state(String value) { this.state = value; return this; }
        Builder country(String value) { this.country = value; return this; }
        Builder employmentType(String value) { this.employmentType = value; return this; }
        Builder experienceMin(Integer value) { this.experienceMin = value; return this; }
        Builder experienceMax(Integer value) { this.experienceMax = value; return this; }
        Builder salaryMin(BigDecimal value) { this.salaryMin = value; return this; }
        Builder salaryMax(BigDecimal value) { this.salaryMax = value; return this; }
        Builder currency(String value) { this.currency = value; return this; }
        Builder postedDate(LocalDate value) { this.postedDate = value; return this; }

        TransformedJob build() {
            return new TransformedJob(title, companyName, "Software", "https://acme.example.invalid",
                    city, state, country, description, employmentType,
                    experienceMin, experienceMax, salaryMin, salaryMax, currency,
                    postedDate, "test", "https://acme.example.invalid/jobs/1", "fingerprint", Set.of());
        }
    }
}
