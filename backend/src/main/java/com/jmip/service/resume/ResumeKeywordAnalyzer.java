package com.jmip.service.resume;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * V8.6: which of a posting's own terms a resume uses, deterministically. Terms come only
 * from the posting's title and description; skills are left to the skill matcher, so the two
 * never count the same thing twice. Nothing is weighted or learned.
 */
@Component
public class ResumeKeywordAnalyzer {

    /** At most this many terms, the posting's most frequent first. */
    static final int MAX_TERMS = 15;
    /** A description term must appear this often to count; title terms always count. */
    static final int MIN_MENTIONS = 2;
    /** Beyond this many uses a term reads as repeated for its own sake. */
    static final int OVERUSE_MENTIONS = 10;

    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9+#.-]*[a-z0-9+#]|[a-z]");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "or", "the", "to", "of", "in", "on", "for", "with", "at", "by", "from", "as", "is", "are",
            "be", "been", "will", "you", "your", "we", "our", "us", "they", "their", "this", "that", "these", "those",
            "it", "its", "who", "what", "which", "can", "able", "must", "should", "would", "have", "has", "had", "do",
            "does", "not", "all", "any", "more", "most", "other", "such", "into", "about", "also", "etc", "per", "via",
            "work", "working", "job", "role", "team", "teams", "company", "join", "looking", "year", "years", "experience",
            "strong", "good", "great", "excellent", "knowledge", "skills", "skill", "ability", "including", "within",
            "across", "using", "use", "new", "well", "plus", "preferred", "required", "requirements", "responsibilities",
            "candidate", "opportunity", "based", "help", "make", "build", "day", "days", "time", "full", "part");

    /** One posting term: how often the posting and the resume use it. */
    public record Term(String term, int jobMentions, int resumeMentions) {
    }

    /** The posting's terms split by whether the resume uses them, and those it repeats heavily. */
    public record Result(List<Term> present, List<Term> missing, List<Term> overused) {
    }

    /**
     * @param skillNames the posting's and resume's skills, excluded here because the skill
     *                   comparison already covers them
     */
    public Result analyze(String jobTitle, String jobDescription, String resumeText, Collection<String> skillNames) {
        Set<String> excluded = skillNames.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, Integer> jobCounts = counts(jobDescription);
        Set<String> titleTerms = Set.copyOf(counts(jobTitle).keySet());
        titleTerms.forEach(term -> jobCounts.merge(term, 1, Integer::sum));

        List<String> terms = jobCounts.entrySet().stream()
                .filter(entry -> !excluded.contains(entry.getKey()))
                .filter(entry -> entry.getValue() >= MIN_MENTIONS || titleTerms.contains(entry.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_TERMS)
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Integer> resumeCounts = counts(resumeText);
        List<Term> present = new ArrayList<>();
        List<Term> missing = new ArrayList<>();
        List<Term> overused = new ArrayList<>();
        for (String term : terms) {
            Term found = new Term(term, jobCounts.get(term), resumeCounts.getOrDefault(term, 0));
            (found.resumeMentions() > 0 ? present : missing).add(found);
            if (found.resumeMentions() >= OVERUSE_MENTIONS) {
                overused.add(found);
            }
        }
        return new Result(present, missing, overused);
    }

    /** The resume's own section headings that are recognised, in a fixed order. */
    public List<String> sections(String resumeText) {
        List<String> found = new ArrayList<>();
        if (resumeText == null) {
            return found;
        }
        SECTIONS.forEach((name, pattern) -> {
            if (pattern.matcher(resumeText).find()) {
                found.add(name);
            }
        });
        return found;
    }

    static final Map<String, Pattern> SECTIONS = sectionPatterns();

    private static Map<String, Pattern> sectionPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("Summary", heading("summary|profile|professional summary|about me|objective"));
        patterns.put("Skills", heading("skills|technical skills|core skills|key skills|technologies"));
        patterns.put("Experience", heading("experience|work experience|professional experience|employment|work history"));
        patterns.put("Projects", heading("projects|key projects"));
        patterns.put("Education", heading("education|academic background|qualifications"));
        patterns.put("Certifications", heading("certifications|certificates|licenses"));
        return patterns;
    }

    /** A heading is the word(s) alone on a line, optionally followed by a colon. */
    private static Pattern heading(String names) {
        return Pattern.compile("(?im)^\\s*(" + names + ")\\s*:?\\s*$");
    }

    private static Map<String, Integer> counts(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null) {
            return counts;
        }
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() > 2 && !STOPWORDS.contains(word) && !word.chars().allMatch(Character::isDigit)) {
                counts.merge(word, 1, Integer::sum);
            }
        }
        return counts;
    }
}
