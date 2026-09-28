package com.jmip.dto.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmip.dto.market.MarketResponses.ModePoint;
import com.jmip.dto.market.MarketResponses.SalaryPoint;

import java.time.LocalDate;
import java.util.List;

/**
 * V8.8: how demand for a role (a V4 job category) or for the whole market has moved over past
 * posting months, from the postings JMIP holds. Historical figures and the one estimate are kept
 * apart: {@link Forecast} is a labelled straight-line extension, never a prediction. Anything
 * without enough data says {@code INSUFFICIENT_DATA} and why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketTrendsResponse(
        String category,
        Period period,
        List<VolumePoint> volume,
        Trend volumeTrend,
        Trend shareTrend,
        SkillTrends skills,
        SalaryTrend salary,
        List<LocationMove> locations,
        List<ModePoint> workModes,
        List<ModeMove> workModeTrends,
        Forecast forecast,
        List<String> notes) {

    /**
     * The months covered. A month in the window with no postings in the whole dataset is listed
     * in {@code monthsWithoutData} and left out of every calculation rather than read as zero.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Period(int requestedMonths, LocalDate fromMonth, LocalDate toMonth, int coveredMonths,
                         List<LocalDate> monthsWithoutData, LocalDate latestPostedDate, String source) {
    }

    /** One month: the selection's postings, all postings, and the selection's share; null when there is no data. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VolumePoint(LocalDate month, Long postings, Long allPostings, Double sharePercentage) {
    }

    /**
     * A deterministic comparison of the earlier and the recent half of the covered months.
     *
     * @param direction INCREASING, DECREASING, STABLE or INSUFFICIENT_DATA
     * @param unit      PERCENT for counts and salaries, PERCENTAGE_POINTS for shares
     * @param change    absent when there is no base to compare with, or not enough data
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trend(String direction, Double change, String unit, LocalDate earlierFrom, LocalDate earlierTo,
                        LocalDate recentFrom, LocalDate recentTo, Double earlierValue, Double recentValue,
                        String basis, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillTrends(String source, LocalDate earlierFrom, LocalDate earlierTo, LocalDate recentFrom,
                              LocalDate recentTo, List<SkillMove> growing, List<SkillMove> declining, String note) {
    }

    public record SkillMove(Long skillId, String skill, String category, long postings, double earlierSharePercentage,
                            double recentSharePercentage, double changeInPercentagePoints, String direction) {
    }

    /** Stated salaries in one currency only; other currencies are never converted or combined. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SalaryTrend(String currency, List<SalaryPoint> series, Trend trend, String note) {
    }

    public record LocationMove(Long locationId, String location, long postings, Trend trend) {
    }

    public record ModeMove(String mode, Trend trend) {
    }

    /**
     * A clearly labelled trend-based estimate of monthly postings, or {@code INSUFFICIENT_DATA}.
     *
     * @param status ESTIMATE or INSUFFICIENT_DATA
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Forecast(String status, String label, String method, Integer basedOnMonths, LocalDate fromMonth,
                           LocalDate toMonth, Double slopePerMonth, Double rSquared, List<EstimatePoint> estimates,
                           String note) {
    }

    public record EstimatePoint(LocalDate month, long estimatedPostings) {
    }
}
