package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * Which skills are gaining or losing demand, measured across historical snapshots.
 *
 * @param window what was compared
 * @param trends one entry per skill that met the volume threshold
 */
public record SkillTrendResponse(Window window, List<SkillTrend> trends) {

    /**
     * The span the comparison covers, split into two halves.
     *
     * <p>Comparing the recent half against the earlier half, rather than the last month
     * against the first, smooths out a single quiet month. Both halves are reported so the
     * comparison can be checked.
     *
     * @param fromPeriod        earliest period included
     * @param toPeriod          latest period included
     * @param periods           RAW COUNT — how many monthly periods were compared
     * @param earlierPeriods    the periods forming the earlier half
     * @param recentPeriods     the periods forming the recent half
     * @param totalJobsInWindow RAW COUNT — dated postings across the whole window
     * @param minJobsThreshold  skills below this many postings in the window are excluded,
     *                          because a move from one posting to two is noise, not a trend
     */
    public record Window(
            LocalDate fromPeriod,
            LocalDate toPeriod,
            int periods,
            List<LocalDate> earlierPeriods,
            List<LocalDate> recentPeriods,
            long totalJobsInWindow,
            int minJobsThreshold) {
    }

    /**
     * @param jobCountInWindow        RAW COUNT — postings mentioning this skill in the window
     * @param earlierSharePercentage  PERCENTAGE — share of postings in the earlier half
     * @param recentSharePercentage   PERCENTAGE — share of postings in the recent half
     * @param changeInPercentagePoints DERIVED — recent minus earlier, in percentage points.
     *                                Points, not percent: a move from 10% to 15% is +5
     *                                points, which is a 50% relative rise, and conflating
     *                                the two is the usual way these charts mislead
     * @param direction               DERIVED — rising, falling, or inside the noise band
     * @param series                  the per-period detail behind the comparison
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillTrend(
            Long skillId,
            String skill,
            String category,
            long jobCountInWindow,
            double earlierSharePercentage,
            double recentSharePercentage,
            double changeInPercentagePoints,
            TrendDirection direction,
            List<TrendPoint> series) {
    }

    /**
     * @param period          first day of the month
     * @param jobCount        RAW COUNT — postings that period mentioning the skill
     * @param totalJobs       RAW COUNT — postings that period, the denominator
     * @param sharePercentage PERCENTAGE — jobCount over totalJobs
     */
    public record TrendPoint(LocalDate period, int jobCount, int totalJobs, double sharePercentage) {
    }
}
