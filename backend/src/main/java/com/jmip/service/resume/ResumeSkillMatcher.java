package com.jmip.service.resume;

import com.jmip.entity.Skill;
import com.jmip.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds known skills in resume text.
 *
 * <p>The vocabulary is the {@code skills} table — the same rows the ETL attaches to job
 * postings. That is deliberate and is what makes a match meaningful: a resume skill and a
 * job skill are literally the same row, so comparing them is an id comparison rather than
 * a string comparison between two dictionaries that could drift apart.
 *
 * <p>Matching works on token runs rather than raw substrings. The text is split into
 * words, and every run of up to N consecutive words is normalised — lower-cased with
 * punctuation and spacing removed — and compared against skills normalised the same way.
 * So "Spring Boot", "spring boot" and "springboot" all reach the same key, while word
 * boundaries stop {@code Java} matching inside {@code JavaScript}, {@code SQL} inside
 * {@code PostgreSQL}, or {@code Git} inside {@code GitHub}.
 */
@Component
public class ResumeSkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(ResumeSkillMatcher.class);

    private static final Pattern WORD_SEPARATOR = Pattern.compile("\\s+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

    private final SkillRepository skillRepository;

    public ResumeSkillMatcher(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    /**
     * @return the skills found, in the order the vocabulary lists them, never containing
     *         the same skill twice
     */
    @Transactional(readOnly = true)
    public Set<Skill> match(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return Set.of();
        }
        // One query for the whole vocabulary rather than a lookup per candidate word.
        return match(resumeText, skillRepository.findAll());
    }

    /**
     * Matching against a supplied vocabulary, so the rules can be tested without a
     * database.
     */
    public Set<Skill> match(String resumeText, List<Skill> vocabulary) {
        if (resumeText == null || resumeText.isBlank() || vocabulary.isEmpty()) {
            return Set.of();
        }

        Map<String, Skill> byNormalizedName = new LinkedHashMap<>();
        int longestSkillInWords = 1;
        for (Skill skill : vocabulary) {
            String key = normalize(skill.getName());
            if (key.isEmpty()) {
                continue;
            }
            // First definition wins, so a duplicate spelling cannot displace a skill.
            byNormalizedName.putIfAbsent(key, skill);
            longestSkillInWords = Math.max(longestSkillInWords, wordCount(skill.getName()));
        }

        String[] words = WORD_SEPARATOR.split(resumeText);
        Set<Skill> found = new LinkedHashSet<>();

        for (int start = 0; start < words.length; start++) {
            int maxRun = Math.min(longestSkillInWords, words.length - start);
            StringBuilder run = new StringBuilder();
            for (int length = 1; length <= maxRun; length++) {
                run.append(words[start + length - 1]);
                Skill skill = lookup(byNormalizedName, normalize(run.toString()));
                if (skill != null) {
                    found.add(skill);
                }
            }
        }

        log.debug("Matched {} skills in {} words of resume text", found.size(), words.length);
        return found;
    }

    /**
     * Direct match first, then one spelling rule: a trailing "js" is dropped, so
     * "React.js" and "Reactjs" reach the skill stored as "React". The rule only ever
     * resolves to a skill that already exists, so it cannot invent vocabulary.
     */
    private static Skill lookup(Map<String, Skill> byNormalizedName, String candidate) {
        if (candidate.isEmpty()) {
            return null;
        }
        Skill direct = byNormalizedName.get(candidate);
        if (direct != null) {
            return direct;
        }
        if (candidate.length() > 4 && candidate.endsWith("js")) {
            return byNormalizedName.get(candidate.substring(0, candidate.length() - 2));
        }
        return null;
    }

    /** Lower-case, letters and digits only, so spacing and punctuation stop mattering. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static int wordCount(String value) {
        return WORD_SEPARATOR.split(value.strip()).length;
    }
}
