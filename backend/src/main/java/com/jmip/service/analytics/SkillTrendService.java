package com.jmip.service.analytics;

import com.jmip.dto.analytics.SkillTrendResponse;
import com.jmip.dto.analytics.SkillTrendResponse.SkillTrend;
import com.jmip.dto.analytics.SkillTrendResponse.TrendPoint;
import com.jmip.dto.analytics.TrendDirection;
import com.jmip.repository.SkillTrendRepository;
import com.jmip.repository.projection.SkillPeriodRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the snapshot history into "this skill is gaining, that one is losing".
 *
 * <p>Two choices decide whether the answer means anything.
 *
 * <p><b>Share, not raw count.</b> If the number of postings doubles, every skill's raw
 * count rises, and a count-based trend would report the entire market as booming. Demand
 * is measured as a skill's share of postings, which only moves when the mix genuinely
 * shifts.
 *
 * <p><b>Halves, not endpoints.</b> Comparing the most recent month against the oldest one
 * makes the result hostage to two individual months. The window is split in half and each
 * half is pooled, so one quiet month does not invent a trend.
 */
@Service
@Transactional(readOnly = true)
public class SkillTrendService {

    private static final Logger log = LoggerFactory.getLogger(SkillTrendService.class);

    /**
     * Changes smaller than this are reported as STABLE. Without a band, rounding alone
     * would label every skill as moving in some direction.
     */
    private static final double STABLE_BAND_PERCENTAGE_POINTS = 1.0;

    private final SkillTrendRepository skillTrendRepository;

    public SkillTrendService(SkillTrendRepository skillTrendRepository) {
        this.skillTrendRepository = skillTrendRepository;
    }

    /**
     * @param months    how many recent periods to compare, capped to what history exists
     * @param minJobs   ignore skills with fewer than this many postings in the window
     * @param direction keep only rising or only falling skills, or all of them
     * @param limit     maximum skills returned
     */
    public SkillTrendResponse trends(int months, int minJobs, TrendDirection direction, int limit) {
        List<LocalDate> allPeriods = skillTrendRepository.findPeriods();
        if (allPeriods.size() < 2) {
            // One period cannot be compared with anything. Report an empty window rather
            // than inventing a direction from a single data point.
            log.debug("Skill trends requested but only {} period(s) of history exist", allPeriods.size());
            return new SkillTrendResponse(emptyWindow(allPeriods, minJobs), List.of());
        }

        List<LocalDate> window = allPeriods.stream().limit(months).sorted().toList();
        int recentCount = (int) Math.ceil(window.size() / 2.0);
        Set<LocalDate> recentPeriods = Set.copyOf(window.subList(window.size() - recentCount, window.size()));
        List<LocalDate> earlierPeriods = window.subList(0, window.size() - recentCount);

        Map<Long, SkillSeries> bySkill = new LinkedHashMap<>();
        Map<LocalDate, Integer> periodTotals = new LinkedHashMap<>();

        for (SkillPeriodRow row : skillTrendRepository.findFromPeriod(window.get(0))) {
            if (!window.contains(row.periodStart())) {
                continue;
            }
            periodTotals.putIfAbsent(row.periodStart(), row.totalJobs());
            bySkill.computeIfAbsent(row.skillId(),
                            key -> new SkillSeries(row.skillId(), row.skillName(), row.category()))
                    .add(row, recentPeriods.contains(row.periodStart()));
        }

        long totalJobsInWindow = periodTotals.values().stream().mapToLong(Integer::longValue).sum();
        long recentTotal = totalIn(periodTotals, recentPeriods);
        long earlierTotal = totalJobsInWindow - recentTotal;

        List<SkillTrend> trends = bySkill.values().stream()
                .filter(series -> series.totalJobs() >= minJobs)
                .map(series -> toTrend(series, earlierTotal, recentTotal, window, periodTotals))
                .filter(trend -> direction == null || direction == trend.direction())
                .sorted(orderFor(direction))
                .limit(limit)
                .toList();

        log.debug("Skill trends over {} periods ({} skills met the {}-job threshold)",
                window.size(), trends.size(), minJobs);

        return new SkillTrendResponse(
                new SkillTrendResponse.Window(
                        window.get(0),
                        window.get(window.size() - 1),
                        window.size(),
                        earlierPeriods,
                        recentPeriods.stream().sorted().toList(),
                        totalJobsInWindow,
                        minJobs),
                trends);
    }

    private SkillTrend toTrend(SkillSeries series, long earlierTotal, long recentTotal,
                               List<LocalDate> window, Map<LocalDate, Integer> periodTotals) {
        double earlierShare = Metrics.percentageOf(series.earlierJobs(), earlierTotal);
        double recentShare = Metrics.percentageOf(series.recentJobs(), recentTotal);
        double change = Math.round((recentShare - earlierShare) * 10.0) / 10.0;

        return new SkillTrend(
                series.skillId(),
                series.skillName(),
                series.category(),
                series.totalJobs(),
                earlierShare,
                recentShare,
                change,
                directionOf(change),
                series.points(window, periodTotals));
    }

    private static TrendDirection directionOf(double changeInPoints) {
        if (changeInPoints >= STABLE_BAND_PERCENTAGE_POINTS) {
            return TrendDirection.RISING;
        }
        if (changeInPoints <= -STABLE_BAND_PERCENTAGE_POINTS) {
            return TrendDirection.FALLING;
        }
        return TrendDirection.STABLE;
    }

    /**
     * Biggest movers first. With no direction asked for, that means largest change in
     * either direction, so the most interesting skills lead whichever way they moved.
     */
    private static Comparator<SkillTrend> orderFor(TrendDirection direction) {
        if (direction == TrendDirection.RISING) {
            return Comparator.comparingDouble(SkillTrend::changeInPercentagePoints).reversed();
        }
        if (direction == TrendDirection.FALLING) {
            return Comparator.comparingDouble(SkillTrend::changeInPercentagePoints);
        }
        return Comparator.comparingDouble(
                (SkillTrend trend) -> Math.abs(trend.changeInPercentagePoints())).reversed()
                .thenComparing(SkillTrend::skill);
    }

    private static long totalIn(Map<LocalDate, Integer> periodTotals, Set<LocalDate> periods) {
        return periodTotals.entrySet().stream()
                .filter(entry -> periods.contains(entry.getKey()))
                .mapToLong(entry -> entry.getValue().longValue())
                .sum();
    }

    private static SkillTrendResponse.Window emptyWindow(List<LocalDate> periods, int minJobs) {
        LocalDate from = periods.isEmpty() ? null : periods.get(periods.size() - 1);
        LocalDate to = periods.isEmpty() ? null : periods.get(0);
        return new SkillTrendResponse.Window(from, to, periods.size(), List.of(), List.of(), 0, minJobs);
    }

    /** Accumulates one skill's periods while the rows stream past. */
    private static final class SkillSeries {
        private final Long skillId;
        private final String skillName;
        private final String category;
        private final Map<LocalDate, SkillPeriodRow> rows = new LinkedHashMap<>();
        private long earlierJobs;
        private long recentJobs;

        private SkillSeries(Long skillId, String skillName, String category) {
            this.skillId = skillId;
            this.skillName = skillName;
            this.category = category;
        }

        private void add(SkillPeriodRow row, boolean isRecent) {
            rows.put(row.periodStart(), row);
            if (isRecent) {
                recentJobs += row.jobCount();
            } else {
                earlierJobs += row.jobCount();
            }
        }

        /** A period the skill never appeared in is a real zero, not a gap in the chart. */
        private List<TrendPoint> points(List<LocalDate> window, Map<LocalDate, Integer> periodTotals) {
            List<TrendPoint> points = new ArrayList<>(window.size());
            for (LocalDate period : window) {
                SkillPeriodRow row = rows.get(period);
                int jobCount = row == null ? 0 : row.jobCount();
                // The period still had postings even if none mentioned this skill, so the
                // denominator comes from the period, not from the missing row.
                int totalJobs = periodTotals.getOrDefault(period, 0);
                points.add(new TrendPoint(period, jobCount, totalJobs,
                        Metrics.percentageOf(jobCount, totalJobs)));
            }
            return points;
        }

        private Long skillId() {
            return skillId;
        }

        private String skillName() {
            return skillName;
        }

        private String category() {
            return category;
        }

        private long earlierJobs() {
            return earlierJobs;
        }

        private long recentJobs() {
            return recentJobs;
        }

        private long totalJobs() {
            return earlierJobs + recentJobs;
        }
    }
}
