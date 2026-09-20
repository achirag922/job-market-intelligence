package com.jmip.service.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {

    @Test
    @DisplayName("percentages are rounded to one decimal place")
    void roundsToOneDecimalPlace() {
        assertThat(Metrics.percentageOf(1, 3)).isEqualTo(33.3);
        assertThat(Metrics.percentageOf(2, 3)).isEqualTo(66.7);
        assertThat(Metrics.percentageOf(34, 138)).isEqualTo(24.6);
    }

    @Test
    @DisplayName("an empty scope yields zero rather than dividing by zero")
    void emptyScopeYieldsZero() {
        assertThat(Metrics.percentageOf(0, 0)).isZero();
        assertThat(Metrics.percentageOf(5, 0)).isZero();
        assertThat(Metrics.percentageOf(5, -1)).isZero();
    }

    @Test
    @DisplayName("a value covering the whole scope is 100 percent")
    void fullCoverageIsOneHundred() {
        assertThat(Metrics.percentageOf(138, 138)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("rank is one based on the first page")
    void rankIsOneBased() {
        assertThat(Metrics.rank(0, 20, 0)).isEqualTo(1);
        assertThat(Metrics.rank(0, 20, 19)).isEqualTo(20);
    }

    @Test
    @DisplayName("rank continues across pages rather than restarting")
    void rankContinuesAcrossPages() {
        // Restarting at 1 on every page would make the second page claim the top skill.
        assertThat(Metrics.rank(1, 20, 0)).isEqualTo(21);
        assertThat(Metrics.rank(2, 15, 3)).isEqualTo(34);
    }
}
