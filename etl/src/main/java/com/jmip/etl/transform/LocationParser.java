package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a free-text location into city, state and country.
 */
@Component
public class LocationParser {

    /**
     * Phrases that mean "no physical location". These map to a null location rather than
     * to a country named "Remote", which would corrupt location analytics.
     */
    private static final Set<String> NOT_A_PLACE = Set.of(
            "remote", "fully remote", "work from home", "wfh", "anywhere",
            "worldwide", "global", "n/a", "na", "none", "unspecified", "not specified");

    /** Absent city and state are normal; an absent country means there is no location at all. */
    public record ParsedLocation(String city, String state, String country) {

        public static final ParsedLocation NONE = new ParsedLocation(null, null, null);

        public boolean isPresent() {
            return country != null;
        }
    }

    public ParsedLocation parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedLocation.NONE;
        }
        String trimmed = raw.trim();
        if (NOT_A_PLACE.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return ParsedLocation.NONE;
        }

        List<String> parts = Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .filter(part -> !NOT_A_PLACE.contains(part.toLowerCase(Locale.ROOT)))
                .toList();

        return switch (parts.size()) {
            case 0 -> ParsedLocation.NONE;
            // A bare token is treated as the country: it is the only interpretation that
            // satisfies the schema's NOT NULL country, and a wrong city is worse than a
            // coarse one.
            case 1 -> new ParsedLocation(null, null, parts.get(0));
            case 2 -> new ParsedLocation(parts.get(0), null, parts.get(1));
            case 3 -> new ParsedLocation(parts.get(0), parts.get(1), parts.get(2));
            // Longer strings such as "Building 4, Elm Street, Austin, Texas, United States"
            // keep the last two parts as state and country and fold the rest into the city.
            default -> new ParsedLocation(
                    String.join(", ", parts.subList(0, parts.size() - 2)),
                    parts.get(parts.size() - 2),
                    parts.get(parts.size() - 1));
        };
    }
}
