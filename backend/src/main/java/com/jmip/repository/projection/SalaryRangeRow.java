package com.jmip.repository.projection;

import java.math.BigDecimal;

/**
 * Salary figures for one currency.
 *
 * <p>Grouped by currency because there is no exchange rate anywhere in this system, and
 * averaging rupees with dollars produces a number that looks like a salary and means
 * nothing.
 *
 * @param currency    ISO code, already trimmed of the padding the CHAR column adds
 * @param jobCount    postings in this currency that state a minimum
 * @param lowestMin   the smallest stated minimum
 * @param highestMax  the largest stated maximum, null when none state one
 * @param averageMin  mean of the stated minimums
 * @param averageMax  mean of the stated maximums, null when none state one
 */
public record SalaryRangeRow(
        String currency,
        long jobCount,
        BigDecimal lowestMin,
        BigDecimal highestMax,
        Double averageMin,
        Double averageMax) {
}
