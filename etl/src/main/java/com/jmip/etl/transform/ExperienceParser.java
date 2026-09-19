package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the years-of-experience requirement out of free text.
 *
 * <p>Handles the forms datasets actually publish: {@code "3-5 years"}, {@code "5+ years"},
 * {@code "up to 4 years"}, {@code "2 to 4 yrs"} and a bare {@code "6"}.
 */
@Component
public class ExperienceParser {

    private static final int IMPLAUSIBLE_YEARS = 60;

    private static final Set<String> MEANS_UNSPECIFIED = Set.of(
            "not specified", "unspecified", "n/a", "na", "none", "any", "fresher", "entry level");

    private static final Pattern RANGE = Pattern.compile(
            "(\\d{1,2})\\s*(?:-|–|—|to|until)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    /** "5+", where the plus itself carries the meaning. */
    private static final Pattern AT_LEAST_PLUS = Pattern.compile("(\\d{1,2})\\s*\\+");
    /** "at least 5", "minimum of 5", where a word carries it and there is no plus sign. */
    private static final Pattern AT_LEAST_PHRASE = Pattern.compile(
            "(?:at\\s+least|minimum(?:\\s+of)?|min\\.?|over|more\\s+than)\\s*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_MOST = Pattern.compile(
            "(?:up\\s+to|less\\s+than|under|max(?:imum)?\\.?\\s*(?:of\\s+)?)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE = Pattern.compile("(\\d{1,2})");

    /** Either bound may be absent: "5+ years" has no maximum, "up to 4" has no minimum. */
    public record ExperienceRange(Integer min, Integer max) {

        public static final ExperienceRange NONE = new ExperienceRange(null, null);
    }

    public ExperienceRange parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExperienceRange.NONE;
        }
        String text = raw.trim();
        if (MEANS_UNSPECIFIED.contains(text.toLowerCase(Locale.ROOT))) {
            return ExperienceRange.NONE;
        }

        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            return validated(text, Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)));
        }
        // "up to N" is checked before "N+" so that neither pattern claims the other's text.
        Matcher atMost = AT_MOST.matcher(text);
        if (atMost.find()) {
            return validated(text, null, Integer.parseInt(atMost.group(1)));
        }
        Matcher atLeastPlus = AT_LEAST_PLUS.matcher(text);
        if (atLeastPlus.find()) {
            return validated(text, Integer.parseInt(atLeastPlus.group(1)), null);
        }
        Matcher atLeastPhrase = AT_LEAST_PHRASE.matcher(text);
        if (atLeastPhrase.find()) {
            return validated(text, Integer.parseInt(atLeastPhrase.group(1)), null);
        }
        Matcher single = SINGLE.matcher(text);
        if (single.find()) {
            int years = Integer.parseInt(single.group(1));
            return validated(text, years, years);
        }
        throw new ValueParseException("Unrecognised experience value: '" + raw + "'");
    }

    private ExperienceRange validated(String raw, Integer min, Integer max) {
        if (min != null && min > IMPLAUSIBLE_YEARS || max != null && max > IMPLAUSIBLE_YEARS) {
            throw new ValueParseException("Implausible experience value: '" + raw + "'");
        }
        if (min != null && max != null && max < min) {
            throw new ValueParseException("Experience range is inverted: '" + raw + "'");
        }
        return new ExperienceRange(min, max);
    }
}
