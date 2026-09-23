package com.jmip.service.analytics;

/**
 * The experience bands reported by the distribution endpoint.
 *
 * <p>Bands are half open — the lower bound is included, the upper excluded — so a
 * requirement of exactly 2 years falls in {@code TWO_TO_FIVE} and is counted once.
 * Written as "0–2, 2–5, 5–8, 8+", those boundaries would otherwise be ambiguous.
 *
 * <p>A posting is placed by its <em>minimum</em> required experience, because that is the
 * bar a candidate has to clear. Placing it by the maximum would describe the ceiling of
 * the range, which is not what "jobs needing 2–5 years" means to anyone reading it.
 */
public enum ExperienceBucket {

    ZERO_TO_TWO("0–2 years", 0, 2),
    TWO_TO_FIVE("2–5 years", 2, 5),
    FIVE_TO_EIGHT("5–8 years", 5, 8),
    EIGHT_PLUS("8+ years", 8, null),
    /** Kept as its own band rather than dropped, so the bands always sum to the total. */
    UNSPECIFIED("Not specified", null, null);

    private final String label;
    private final Integer minYears;
    private final Integer maxYearsExclusive;

    ExperienceBucket(String label, Integer minYears, Integer maxYearsExclusive) {
        this.label = label;
        this.minYears = minYears;
        this.maxYearsExclusive = maxYearsExclusive;
    }

    public String label() {
        return label;
    }

    public Integer minYears() {
        return minYears;
    }

    public Integer maxYearsExclusive() {
        return maxYearsExclusive;
    }

    /**
     * The band a URL-friendly slug names — {@code 0-2}, {@code 2-5}, {@code 5-8}, {@code 8+}
     * or {@code unspecified}.
     *
     * <p>Lives here so job search filters on exactly the bands the distribution endpoint
     * reports. A second definition in the search code would drift, and "2–5 years" would
     * then mean one thing on the chart and another in the filter beside it.
     *
     * @throws IllegalArgumentException for an unknown slug
     */
    public static ExperienceBucket fromSlug(String slug) {
        return switch (slug.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "0-2" -> ZERO_TO_TWO;
            case "2-5" -> TWO_TO_FIVE;
            case "5-8" -> FIVE_TO_EIGHT;
            case "8+" -> EIGHT_PLUS;
            case "unspecified" -> UNSPECIFIED;
            default -> throw new IllegalArgumentException("Unknown experience band: " + slug);
        };
    }

    /** @param experienceMin minimum years the posting asks for, or null if it did not say */
    public static ExperienceBucket of(Integer experienceMin) {
        if (experienceMin == null) {
            return UNSPECIFIED;
        }
        if (experienceMin < ZERO_TO_TWO.maxYearsExclusive) {
            return ZERO_TO_TWO;
        }
        if (experienceMin < TWO_TO_FIVE.maxYearsExclusive) {
            return TWO_TO_FIVE;
        }
        if (experienceMin < FIVE_TO_EIGHT.maxYearsExclusive) {
            return FIVE_TO_EIGHT;
        }
        return EIGHT_PLUS;
    }
}
