package com.jmip.service.resume;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * V9.3: which of a posting's listed skills it marks as optional, read from its own wording. Postings
 * carry no required/optional flag, so the only evidence is the text: a skill mentioned only in
 * sentences or sections that say "nice to have", "a plus", "bonus", "preferred", "desirable",
 * "optional" or "advantageous" is optional. Every other listed skill, including one the text never
 * names, is required: when in doubt, nothing is downgraded.
 */
final class SkillImportance {

    private static final Pattern OPTIONAL_CUE = Pattern.compile(
            "\\b(nice[- ]to[- ]have|good[- ]to[- ]have|(is )?a (big )?plus|bonus|preferred|desirable|optional|advantageous)\\b",
            Pattern.CASE_INSENSITIVE);
    /** A heading line such as "Nice to have:" or "Requirements:". */
    private static final Pattern HEADING = Pattern.compile("^\\s*[\\p{L} /&-]{2,60}:\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;])\\s+");

    private SkillImportance() {
    }

    /** The names, among {@code skillNames}, that the description only mentions as optional. */
    static Set<String> optional(String description, Collection<String> skillNames) {
        Set<String> optional = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (description == null || description.isBlank()) {
            return optional;
        }
        List<String> required = new ArrayList<>();
        List<String> wishful = new ArrayList<>();
        boolean inOptionalSection = false;
        for (String line : description.split("\\R")) {
            if (line.isBlank()) {
                inOptionalSection = false;
                continue;
            }
            if (HEADING.matcher(line).matches()) {
                inOptionalSection = OPTIONAL_CUE.matcher(line).find();
                continue;
            }
            for (String sentence : SENTENCE_END.split(line)) {
                (inOptionalSection || OPTIONAL_CUE.matcher(sentence).find() ? wishful : required).add(sentence);
            }
        }
        for (String name : skillNames) {
            Pattern mention = Pattern.compile("(?<![\\p{Alnum}])" + Pattern.quote(name.toLowerCase(Locale.ROOT))
                    + "(?![\\p{Alnum}])");
            boolean inWishful = wishful.stream().anyMatch(s -> mention.matcher(s.toLowerCase(Locale.ROOT)).find());
            boolean inRequired = required.stream().anyMatch(s -> mention.matcher(s.toLowerCase(Locale.ROOT)).find());
            if (inWishful && !inRequired) {
                optional.add(name);
            }
        }
        return optional;
    }
}
