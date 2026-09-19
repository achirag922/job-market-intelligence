package com.jmip.etl.transform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps whatever vocabulary a source uses onto the six values the database accepts.
 *
 * <p>An unrecognised value becomes {@code null} rather than a rejection: employment type
 * is optional, and losing one optional field is a smaller loss than discarding an
 * otherwise good posting. Unknown values are logged so the mapping can be extended.
 */
@Component
public class EmploymentTypeNormalizer {

    private static final Logger log = LoggerFactory.getLogger(EmploymentTypeNormalizer.class);

    /** Ordered: the first matching key wins, so "full time contract" resolves to CONTRACT. */
    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("intern", "INTERNSHIP");
        PATTERNS.put("trainee", "INTERNSHIP");
        PATTERNS.put("apprentice", "INTERNSHIP");
        PATTERNS.put("freelance", "FREELANCE");
        PATTERNS.put("contract", "CONTRACT");
        PATTERNS.put("contractor", "CONTRACT");
        PATTERNS.put("c2c", "CONTRACT");
        PATTERNS.put("b2b", "CONTRACT");
        PATTERNS.put("temporary", "TEMPORARY");
        PATTERNS.put("temp", "TEMPORARY");
        PATTERNS.put("seasonal", "TEMPORARY");
        PATTERNS.put("parttime", "PART_TIME");
        PATTERNS.put("fulltime", "FULL_TIME");
        PATTERNS.put("permanent", "FULL_TIME");
        PATTERNS.put("regular", "FULL_TIME");
    }

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Map.Entry<String, String> entry : PATTERNS.entrySet()) {
            if (compact.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        log.debug("Unrecognised employment type '{}', storing null", raw);
        return null;
    }
}
