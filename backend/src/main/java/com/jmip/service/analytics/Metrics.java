package com.jmip.service.analytics;

/**
 * The arithmetic behind every derived figure the analytics endpoints report.
 *
 * <p>Defined once so that "percentage" means the same thing on every endpoint, and so
 * the rounding rule is stated in exactly one place rather than re-invented per service.
 */
public final class Metrics {

    private Metrics() {
    }

    /**
     * A count as a share of a total, to one decimal place.
     *
     * <p>One decimal place is deliberate: these are counts of job postings, and further
     * precision would imply an accuracy the underlying data does not have.
     *
     * @param total the denominator; zero yields zero rather than a division by zero
     */
    public static double percentageOf(long count, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(count * 1000.0 / total) / 10.0;
    }

    /**
     * Position in a ranking, 1 based and continuing correctly across pages.
     *
     * @param pageNumber   zero based page
     * @param pageSize     rows per page
     * @param indexOnPage  zero based position within the page
     */
    public static int rank(int pageNumber, int pageSize, int indexOnPage) {
        return pageNumber * pageSize + indexOnPage + 1;
    }
}
