package com.jmip.service.analytics;

import com.jmip.dto.analytics.SalaryRangeResponse;
import com.jmip.repository.AnalyticsRepository;
import com.jmip.repository.projection.SalaryRangeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stated salaries, reported per currency.
 *
 * <p>Salary is the weakest data in this dataset and this service is built to say so rather
 * than to paper over it. Two thirds of postings state a minimum, across eight currencies,
 * with no exchange rates anywhere in the system. That supports exactly one honest
 * operation: group by currency, report the sample size next to every figure, and leave
 * thin samples out.
 *
 * <p>What it deliberately does not do is produce a single headline salary number. There is
 * no arithmetic that turns rupees and dollars into one average, and a caller asking "what
 * does this pay" is better served by nothing than by a confident wrong number.
 */
@Service
@Transactional(readOnly = true)
public class SalaryAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(SalaryAnalyticsService.class);

    private final AnalyticsRepository analyticsRepository;

    public SalaryAnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    /**
     * @param category    exact job category, or null for the whole dataset
     * @param minimumSample currencies with fewer postings than this are dropped, because a
     *                      range over one or two postings describes those postings and
     *                      nothing else
     */
    public List<SalaryRangeResponse> salaryRanges(String category, int minimumSample) {
        List<SalaryRangeRow> rows = analyticsRepository.findSalaryRanges(category);

        List<SalaryRangeResponse> usable = rows.stream()
                .filter(row -> row.jobCount() >= minimumSample)
                .map(SalaryAnalyticsService::toResponse)
                .toList();

        log.debug("Salary ranges category={} currencies={} usable={}",
                category, rows.size(), usable.size());
        return usable;
    }

    private static SalaryRangeResponse toResponse(SalaryRangeRow row) {
        return new SalaryRangeResponse(
                row.currency(),
                row.jobCount(),
                row.lowestMin(),
                row.highestMax(),
                round(row.averageMin()),
                round(row.averageMax()));
    }

    /** Salaries are whole-unit figures; decimal places on an average only imply precision. */
    private static Long round(Double value) {
        return value == null ? null : Math.round(value);
    }
}
