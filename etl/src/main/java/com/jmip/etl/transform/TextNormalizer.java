package com.jmip.etl.transform;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Whitespace, punctuation and markup cleanup shared by every field.
 */
@Component
public class TextNormalizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_ENTITY_SPACE = Pattern.compile("&nbsp;|&#160;", Pattern.CASE_INSENSITIVE);
    private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2007\\u202F]+");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("(\\R\\s*){3,}");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s,;.\\-–—]+$");

    /** Trim, collapse runs of spaces, and turn an empty result into {@code null}. */
    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = HORIZONTAL_WHITESPACE.matcher(value).replaceAll(" ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Job titles get the lightest possible touch: whitespace only. Seniority words,
     * bracketed qualifiers and punctuation all carry meaning a reader relies on, and
     * rewriting them would change what the employer actually advertised.
     */
    public String normalizeTitle(String title) {
        return normalize(stripMarkup(title));
    }

    /**
     * Company names are normalised only where it is safe. Legal suffixes such as "Ltd"
     * or "Inc" are deliberately left alone: stripping them merges genuinely different
     * legal entities and cannot be undone once the rows are written.
     */
    public String normalizeCompany(String company) {
        String cleaned = normalize(stripMarkup(company));
        if (cleaned == null) {
            return null;
        }
        cleaned = stripSurroundingQuotes(cleaned);
        cleaned = TRAILING_PUNCTUATION.matcher(cleaned).replaceAll("");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Descriptions keep their paragraph structure, because skill extraction and anything
     * a human later reads both depend on it. Markup and runaway blank lines go.
     */
    public String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String cleaned = stripMarkup(description);
        cleaned = HORIZONTAL_WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = EXCESS_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        cleaned = cleaned.lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String stripMarkup(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = HTML_ENTITY_SPACE.matcher(value).replaceAll(" ");
        return HTML_TAG.matcher(cleaned).replaceAll(" ");
    }

    private String stripSurroundingQuotes(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
