package com.jmip.etl.transform;

import com.jmip.etl.config.SkillDictionaryProperties;
import com.jmip.etl.config.SkillDictionaryProperties.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds known skills in a posting's title and description.
 *
 * <p>Matching is dictionary driven and case-insensitive, and every term is wrapped in
 * word boundaries. That boundary is what keeps the obvious false positives out:
 * {@code Java} does not match inside {@code JavaScript}, {@code SQL} does not match
 * inside {@code PostgreSQL} or {@code NoSQL}, and {@code Git} does not match inside
 * {@code GitHub}.
 *
 * <p>The dictionary can also declare that one skill supersedes another. A posting that
 * mentions only Spring Boot yields Spring Boot alone, rather than inflating demand for
 * Spring as well.
 */
@Component
public class SkillExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkillExtractor.class);

    private final List<CompiledSkill> skills;

    public SkillExtractor(SkillDictionaryProperties properties) {
        this.skills = properties.skills().stream().map(CompiledSkill::new).toList();
        log.info("Skill dictionary loaded with {} skills", skills.size());
        if (skills.isEmpty()) {
            log.warn("Skill dictionary is empty: no skills will be extracted");
        }
    }

    /**
     * @return canonical skill names found, in dictionary order so the result is stable
     */
    public Set<String> extract(String title, String description) {
        String haystack = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        if (haystack.isBlank()) {
            return Set.of();
        }

        Set<String> matched = new LinkedHashSet<>();
        for (CompiledSkill skill : skills) {
            if (skill.matches(haystack)) {
                matched.add(skill.name());
            }
        }

        // Drop skills made redundant by a more specific match.
        Set<String> result = new LinkedHashSet<>(matched);
        for (CompiledSkill skill : skills) {
            if (matched.contains(skill.name())
                    && skill.supersededBy().stream().anyMatch(matched::contains)) {
                result.remove(skill.name());
            }
        }
        return result;
    }

    /** Category for a canonical skill name, used when the skill row is first created. */
    public String categoryOf(String skillName) {
        return skills.stream()
                .filter(skill -> skill.name().equals(skillName))
                .map(CompiledSkill::category)
                .findFirst()
                .orElse(null);
    }

    private record CompiledSkill(String name, String category, List<Pattern> patterns, Set<String> supersededBy) {

        CompiledSkill(SkillDefinition definition) {
            this(definition.name(),
                    definition.category(),
                    compile(definition.allTerms()),
                    Set.copyOf(definition.supersededBy()));
        }

        private static List<Pattern> compile(List<String> terms) {
            List<Pattern> compiled = new ArrayList<>();
            for (String term : terms) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                // Lookarounds rather than \b: they behave correctly for terms that start
                // or end with punctuation, such as "Node.js" or ".NET", where \b would
                // anchor in the wrong place.
                compiled.add(Pattern.compile(
                        "(?<![\\w])" + Pattern.quote(term.trim()) + "(?![\\w])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            return List.copyOf(compiled);
        }

        boolean matches(String haystack) {
            return patterns.stream().anyMatch(pattern -> pattern.matcher(haystack).find());
        }
    }

    /** Lower-cased canonical name, used as the cache and lookup key for {@code skills.name}. */
    public static String lookupKey(String skillName) {
        return skillName.toLowerCase(Locale.ROOT);
    }
}
