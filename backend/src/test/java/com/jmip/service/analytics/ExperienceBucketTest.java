package com.jmip.service.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceBucketTest {

    @ParameterizedTest
    @CsvSource({
            "0, ZERO_TO_TWO",
            "1, ZERO_TO_TWO",
            "2, TWO_TO_FIVE",
            "4, TWO_TO_FIVE",
            "5, FIVE_TO_EIGHT",
            "7, FIVE_TO_EIGHT",
            "8, EIGHT_PLUS",
            "20, EIGHT_PLUS",
    })
    @DisplayName("years land in the expected band")
    void yearsLandInExpectedBand(int years, ExperienceBucket expected) {
        assertThat(ExperienceBucket.of(years)).isEqualTo(expected);
    }

    @Test
    @DisplayName("boundaries belong to the band above, so no posting is counted twice")
    void boundariesBelongToBandAbove() {
        // Written as "0–2, 2–5" the boundary is ambiguous; half-open settles it.
        assertThat(ExperienceBucket.of(2)).isEqualTo(ExperienceBucket.TWO_TO_FIVE);
        assertThat(ExperienceBucket.of(5)).isEqualTo(ExperienceBucket.FIVE_TO_EIGHT);
        assertThat(ExperienceBucket.of(8)).isEqualTo(ExperienceBucket.EIGHT_PLUS);
    }

    @Test
    @DisplayName("an unstated requirement is its own band, not silently dropped")
    void unstatedRequirementIsItsOwnBand() {
        assertThat(ExperienceBucket.of(null)).isEqualTo(ExperienceBucket.UNSPECIFIED);
    }

    @Test
    @DisplayName("the bands are exhaustive, so counts always reconcile to the total")
    void bandsAreExhaustive() {
        for (int years = 0; years <= 60; years++) {
            assertThat(ExperienceBucket.of(years)).isNotNull();
        }
    }

    @Test
    @DisplayName("bounds are reported for charting")
    void boundsAreReported() {
        assertThat(ExperienceBucket.TWO_TO_FIVE.minYears()).isEqualTo(2);
        assertThat(ExperienceBucket.TWO_TO_FIVE.maxYearsExclusive()).isEqualTo(5);
        assertThat(ExperienceBucket.EIGHT_PLUS.maxYearsExclusive()).isNull();
        assertThat(ExperienceBucket.UNSPECIFIED.minYears()).isNull();
    }
}
