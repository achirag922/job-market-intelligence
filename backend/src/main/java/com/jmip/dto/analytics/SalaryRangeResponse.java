package com.jmip.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * What postings state they pay, in one currency.
 *
 * <p>Never combined across currencies. There are no exchange rates in this system, so a
 * pooled figure would be a number with no unit, and the only honest presentation is one
 * row per currency with its own sample size attached.
 *
 * @param currency   ISO code
 * @param jobCount   RAW COUNT — postings in this currency that state a minimum. Read it
 *                   before the figures: a range over three postings is not a market rate
 * @param lowestMin  RAW — smallest stated minimum
 * @param highestMax RAW — largest stated maximum, null when no posting states one
 * @param averageMin DERIVED — mean of stated minimums, to the nearest whole unit
 * @param averageMax DERIVED — mean of stated maximums, null when none state one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalaryRangeResponse(
        String currency,
        long jobCount,
        BigDecimal lowestMin,
        BigDecimal highestMax,
        Long averageMin,
        Long averageMax) {
}
