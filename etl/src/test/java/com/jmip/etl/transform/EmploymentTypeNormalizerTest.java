package com.jmip.etl.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V10.9: the many spellings of an employment type collapse to one value; unknown ones are not guessed. */
class EmploymentTypeNormalizerTest {

    private final EmploymentTypeNormalizer normalizer = new EmploymentTypeNormalizer();

    @ParameterizedTest
    @CsvSource({
            "Full-time, FULL_TIME", "FULL TIME, FULL_TIME", "Permanent, FULL_TIME", "part_time, PART_TIME",
            "Contract, CONTRACT", "Contractor (B2B), CONTRACT", "Freelance, FREELANCE", "Internship, INTERNSHIP",
            "Graduate trainee, INTERNSHIP", "Temporary, TEMPORARY", "Seasonal, TEMPORARY",
            // Ordered rules: the more specific kind wins over "full time".
            "Full time contract, CONTRACT", "Full-time internship, INTERNSHIP"})
    @DisplayName("spellings and combinations map to one employment type")
    void normalises(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "Flexible", "See description"})
    @DisplayName("blank or unrecognised values are stored as null rather than guessed")
    void unknown(String raw) {
        assertThat(normalizer.normalize(raw)).isNull();
    }
}
