package com.jmip.service.analytics;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups job titles that describe the same role.
 *
 * <p>"Safe" is the operative word. Only three things are removed, each of which describes
 * the level or the arrangement rather than the role itself:
 *
 * <ul>
 *   <li>seniority prefixes — "Senior Backend Engineer" and "Junior Backend Engineer" are
 *       both backend engineer roles;</li>
 *   <li>parenthetical qualifiers — "(Remote)", "(EU)", "(m/f/d)";</li>
 *   <li>trailing level markers — "Engineer II", "Analyst 3".</li>
 * </ul>
 *
 * <p>Everything else is left alone. In particular, text after a dash or comma is kept:
 * "Engineer - Payments" is a different job from "Engineer - Search", and merging them
 * would invent a trend that is not in the data.
 *
 * <p>The stored title is never modified; this produces a grouping key for analytics only.
 */
@Component
public class JobTitleNormalizer {

    /** Removed only at the start of a title, where they qualify the role that follows. */
    private static final List<String> SENIORITY_PREFIXES = List.of(
            "intern", "internship", "trainee", "graduate", "entry level", "entry-level",
            "junior", "jr", "jr.", "associate", "mid level", "mid-level", "mid",
            "senior", "senior level", "sr", "sr.", "lead", "staff", "principal", "chief", "head of");

    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final Pattern TRAILING_LEVEL =
            Pattern.compile("\\s+(?:i{1,3}|iv|v|vi{1,3}|[1-9])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Words that look wrong in Title Case and are restored to their usual form. */
    private static final Map<String, String> ACRONYMS = Map.ofEntries(
            Map.entry("qa", "QA"), Map.entry("sre", "SRE"), Map.entry("ml", "ML"),
            Map.entry("ai", "AI"), Map.entry("it", "IT"), Map.entry("ux", "UX"),
            Map.entry("ui", "UI"), Map.entry("devops", "DevOps"), Map.entry("ios", "iOS"),
            Map.entry("api", "API"), Map.entry("sql", "SQL"), Map.entry("bi", "BI"),
            Map.entry("erp", "ERP"), Map.entry("crm", "CRM"), Map.entry("hr", "HR"));

    /**
     * @return the canonical title, or the trimmed original when normalisation would leave
     *         nothing behind. A title that is only a seniority word, such as "Intern",
     *         keeps its own identity rather than collapsing into an empty group.
     */
    public String normalize(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "Unknown";
        }
        String working = rawTitle.toLowerCase(Locale.ROOT).trim();
        working = PARENTHETICAL.matcher(working).replaceAll(" ");
        working = WHITESPACE.matcher(working).replaceAll(" ").trim();
        working = stripSeniorityPrefix(working);
        working = TRAILING_LEVEL.matcher(working).replaceAll("");
        working = WHITESPACE.matcher(working).replaceAll(" ").trim();

        if (working.isEmpty()) {
            return titleCase(rawTitle.toLowerCase(Locale.ROOT).trim());
        }
        return titleCase(working);
    }

    /**
     * Strips repeatedly, so "senior lead engineer" reduces to "engineer", but never to
     * nothing: the last remaining word is kept whatever it is.
     */
    private String stripSeniorityPrefix(String title) {
        String working = title;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : SENIORITY_PREFIXES) {
                String candidate = prefix + " ";
                if (working.startsWith(candidate)) {
                    String remainder = working.substring(candidate.length()).trim();
                    if (!remainder.isEmpty()) {
                        working = remainder;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return working;
    }

    private String titleCase(String value) {
        return WHITESPACE.splitAsStream(value)
                .filter(word -> !word.isEmpty())
                .map(word -> {
                    String acronym = ACRONYMS.get(word);
                    if (acronym != null) {
                        return acronym;
                    }
                    return Character.toUpperCase(word.charAt(0)) + word.substring(1);
                })
                .reduce((a, b) -> a + " " + b)
                .orElse(value);
    }
}
