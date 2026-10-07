package com.jmip.service.market;

import com.jmip.dto.analytics.TrendDirection;
import com.jmip.dto.market.MarketTrendsResponse.EstimatePoint;
import com.jmip.dto.market.MarketTrendsResponse.Forecast;
import com.jmip.dto.market.MarketTrendsResponse.Trend;
import com.jmip.service.analytics.SkillTrendService;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V8.8: the few, transparent calculations behind market trends. No model, no learned weights:
 *
 * <ul>
 *   <li>The covered months are split in two, the later half (rounded up) being "recent", as the
 *       V6.1 skill trends do, and the two halves are compared.</li>
 *   <li>Counts and salaries change by a percentage; a change under {@value #STABLE_PERCENT}% is
 *       STABLE. Shares change in percentage points, with the skill trends' own stable band.</li>
 *   <li>The estimate is an ordinary least-squares straight line through the covered months (by
 *       their real calendar position, so gaps are handled), extended {@value #HORIZON} months,
 *       and only from {@value #MIN_FORECAST_MONTHS} covered months.</li>
 * </ul>
 */
public final class TrendMath {

    public static final double STABLE_PERCENT = 5.0;
    public static final int MIN_FORECAST_MONTHS = 6;
    public static final int HORIZON = 3;

    public static final String INCREASING = "INCREASING";
    public static final String DECREASING = "DECREASING";
    public static final String STABLE = "STABLE";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    static final String ESTIMATE_LABEL = "Estimate, not a prediction: a straight line fitted to past monthly postings and "
            + "extended " + HORIZON + " months. Hiring can change for reasons this data does not show.";
    static final String ESTIMATE_METHOD = "Ordinary least squares on postings per covered posting month, months placed by "
            + "calendar position; estimates below zero are shown as zero.";

    private TrendMath() {
    }

    /** The covered months in two halves; usable when both have at least one month. */
    public record Split(List<LocalDate> earlier, List<LocalDate> recent) {

        public boolean usable() {
            return !earlier.isEmpty() && !recent.isEmpty();
        }
    }

    public static Split split(List<LocalDate> covered) {
        int recentCount = (int) Math.ceil(covered.size() / 2.0);
        return new Split(List.copyOf(covered.subList(0, covered.size() - recentCount)),
                List.copyOf(covered.subList(covered.size() - recentCount, covered.size())));
    }

    public static long sum(Map<LocalDate, Long> values, List<LocalDate> months) {
        return months.stream().mapToLong(month -> values.getOrDefault(month, 0L)).sum();
    }

    /** The mean per month over the given (covered) months. */
    public static double average(Map<LocalDate, Long> values, List<LocalDate> months) {
        return months.isEmpty() ? 0 : (double) sum(values, months) / months.size();
    }

    /** Percentage change of a count or amount between the two halves. */
    public static Trend percentTrend(double earlier, double recent, Split split, String basis) {
        if (!split.usable()) {
            return insufficient(basis, "At least two months with data are needed to compare periods.");
        }
        if (earlier == 0) {
            return trend(recent > 0 ? INCREASING : STABLE, null, "PERCENT", split, earlier, recent, basis,
                    recent > 0 ? "None in the earlier period, so no percentage can be given." : null);
        }
        double change = round1((recent - earlier) / earlier * 100.0);
        String direction = Math.abs(change) < STABLE_PERCENT ? STABLE : change > 0 ? INCREASING : DECREASING;
        return trend(direction, change, "PERCENT", split, earlier, recent, basis, null);
    }

    /** Change of a share in percentage points, with the V6.1 skill trends' stable band. */
    public static Trend pointsTrend(double earlierShare, double recentShare, Split split, String basis) {
        if (!split.usable()) {
            return insufficient(basis, "At least two months with data are needed to compare periods.");
        }
        double change = round1(recentShare - earlierShare);
        return trend(word(SkillTrendService.directionOf(change)), change, "PERCENTAGE_POINTS", split, earlierShare,
                recentShare, basis, null);
    }

    public static Trend insufficient(String basis, String reason) {
        return new Trend(INSUFFICIENT_DATA, null, null, null, null, null, null, null, null, basis, reason);
    }

    /** RISING/FALLING/STABLE of the skill trends, in this API's words. */
    public static String word(TrendDirection direction) {
        return switch (direction) {
            case RISING -> INCREASING;
            case FALLING -> DECREASING;
            case STABLE -> STABLE;
        };
    }

    /** A least-squares line through the covered months, extended {@value #HORIZON} months past the last one. */
    public static Forecast forecast(List<LocalDate> covered, Map<LocalDate, Long> values) {
        if (covered.size() < MIN_FORECAST_MONTHS) {
            return insufficientForecast("Insufficient data: an estimate needs at least " + MIN_FORECAST_MONTHS
                    + " months with data; this selection has " + covered.size() + ".");
        }
        LocalDate first = covered.get(0);
        int n = covered.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = ChronoUnit.MONTHS.between(first, covered.get(i));
            y[i] = values.getOrDefault(covered.get(i), 0L);
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double sxx = 0;
        double sxy = 0;
        double ssTotal = 0;
        for (int i = 0; i < n; i++) {
            sxx += (x[i] - meanX) * (x[i] - meanX);
            sxy += (x[i] - meanX) * (y[i] - meanY);
            ssTotal += (y[i] - meanY) * (y[i] - meanY);
        }
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        double ssResidual = 0;
        for (int i = 0; i < n; i++) {
            double fitted = intercept + slope * x[i];
            ssResidual += (y[i] - fitted) * (y[i] - fitted);
        }
        Double rSquared = ssTotal == 0 ? null : round3(1 - ssResidual / ssTotal);

        LocalDate last = covered.get(n - 1);
        List<EstimatePoint> estimates = new ArrayList<>();
        for (int step = 1; step <= HORIZON; step++) {
            LocalDate month = last.plusMonths(step);
            double value = intercept + slope * ChronoUnit.MONTHS.between(first, month);
            estimates.add(new EstimatePoint(month, Math.max(0, Math.round(value))));
        }
        return new Forecast("ESTIMATE", ESTIMATE_LABEL, ESTIMATE_METHOD, n, first, last, round1(slope), rSquared, estimates,
                rSquared == null ? "Every covered month had the same count, so the line is flat." : null);
    }

    public static Forecast insufficientForecast(String note) {
        return new Forecast(INSUFFICIENT_DATA, ESTIMATE_LABEL, ESTIMATE_METHOD, null, null, null, null, null, List.of(), note);
    }

    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double mean(double[] values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private static Trend trend(String direction, Double change, String unit, Split split, double earlier, double recent,
                               String basis, String note) {
        return new Trend(direction, change, unit, split.earlier().get(0), split.earlier().get(split.earlier().size() - 1),
                split.recent().get(0), split.recent().get(split.recent().size() - 1), round1(earlier), round1(recent),
                basis, note);
    }
}
