package com.jmip.service.market;

import com.jmip.dto.market.MarketTrendsResponse.Forecast;
import com.jmip.dto.market.MarketTrendsResponse.Trend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V8.8: the period split, percentage and point changes, the stable bands, and the straight-line estimate. */
class TrendMathTest {

    private static LocalDate month(int m) {
        return LocalDate.of(2026, m, 1);
    }

    @Test
    @DisplayName("the later half, rounded up, is recent; one month cannot be split")
    void split() {
        TrendMath.Split three = TrendMath.split(List.of(month(1), month(2), month(3)));
        assertThat(three.earlier()).containsExactly(month(1));
        assertThat(three.recent()).containsExactly(month(2), month(3));
        assertThat(TrendMath.split(List.of(month(1))).usable()).isFalse();
    }

    @Test
    @DisplayName("percentage change with a 5% stable band, the periods compared, and no percentage from zero")
    void percentTrend() {
        TrendMath.Split split = TrendMath.split(List.of(month(1), month(2)));
        Trend up = TrendMath.percentTrend(10, 12, split, "basis");
        assertThat(up.direction()).isEqualTo("INCREASING");
        assertThat(up.change()).isEqualTo(20.0);
        assertThat(up.unit()).isEqualTo("PERCENT");
        assertThat(up.earlierFrom()).isEqualTo(month(1));
        assertThat(up.recentTo()).isEqualTo(month(2));
        assertThat(TrendMath.percentTrend(10, 10.4, split, "b").direction()).isEqualTo("STABLE");
        assertThat(TrendMath.percentTrend(10, 5, split, "b").direction()).isEqualTo("DECREASING");
        Trend fromZero = TrendMath.percentTrend(0, 3, split, "b");
        assertThat(fromZero.direction()).isEqualTo("INCREASING");
        assertThat(fromZero.change()).isNull();
        assertThat(TrendMath.percentTrend(1, 2, TrendMath.split(List.of(month(1))), "b").direction())
                .isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    @DisplayName("share changes use the skill trends' one-point stable band")
    void pointsTrend() {
        TrendMath.Split split = TrendMath.split(List.of(month(1), month(2)));
        Trend up = TrendMath.pointsTrend(20, 21.5, split, "b");
        assertThat(up.direction()).isEqualTo("INCREASING");
        assertThat(up.change()).isEqualTo(1.5);
        assertThat(up.unit()).isEqualTo("PERCENTAGE_POINTS");
        assertThat(TrendMath.pointsTrend(20, 20.5, split, "b").direction()).isEqualTo("STABLE");
    }

    @Test
    @DisplayName("the estimate is a least-squares line placed by calendar month, so a missing month does not bend it")
    void forecastLine() {
        Map<LocalDate, Long> values = new LinkedHashMap<>();
        // y = x + 2, with April missing: x = 0, 1, 2, 4, 5, 6.
        List<LocalDate> covered = List.of(month(1), month(2), month(3), month(5), month(6), month(7));
        long[] counts = {2, 3, 4, 6, 7, 8};
        for (int i = 0; i < covered.size(); i++) {
            values.put(covered.get(i), counts[i]);
        }
        Forecast forecast = TrendMath.forecast(covered, values);
        assertThat(forecast.status()).isEqualTo("ESTIMATE");
        assertThat(forecast.slopePerMonth()).isEqualTo(1.0);
        assertThat(forecast.rSquared()).isEqualTo(1.0);
        assertThat(forecast.basedOnMonths()).isEqualTo(6);
        assertThat(forecast.estimates()).extracting(point -> point.month().getMonthValue()).containsExactly(8, 9, 10);
        assertThat(forecast.estimates()).extracting(point -> point.estimatedPostings()).containsExactly(9L, 10L, 11L);
        assertThat(forecast.label()).startsWith("Estimate, not a prediction");
    }

    @Test
    @DisplayName("too few months is insufficient; a flat series has no fit figure; estimates never go below zero")
    void forecastEdges() {
        List<LocalDate> five = List.of(month(1), month(2), month(3), month(4), month(5));
        assertThat(TrendMath.forecast(five, Map.of()).status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(TrendMath.forecast(five, Map.of()).note()).contains("at least 6 months");

        List<LocalDate> six = List.of(month(1), month(2), month(3), month(4), month(5), month(6));
        Map<LocalDate, Long> flat = new LinkedHashMap<>();
        Map<LocalDate, Long> falling = new LinkedHashMap<>();
        for (int i = 0; i < six.size(); i++) {
            flat.put(six.get(i), 4L);
            falling.put(six.get(i), 10L - 2L * i);
        }
        assertThat(TrendMath.forecast(six, flat).rSquared()).isNull();
        assertThat(TrendMath.forecast(six, flat).estimates()).extracting(p -> p.estimatedPostings()).containsExactly(4L, 4L, 4L);
        assertThat(TrendMath.forecast(six, falling).estimates()).extracting(p -> p.estimatedPostings()).containsExactly(0L, 0L, 0L);
    }

    @Test
    @DisplayName("data older than two months before today is called out as not current")
    void staleness() {
        assertThat(MarketTrendsService.staleNote(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 9, 28)))
                .hasValueSatisfying(note -> assertThat(note).contains("2026-03", "not today"));
        assertThat(MarketTrendsService.staleNote(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 9, 28))).isEmpty();
    }
}
