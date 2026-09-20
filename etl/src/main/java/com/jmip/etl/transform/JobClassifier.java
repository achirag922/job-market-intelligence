package com.jmip.etl.transform;

import com.jmip.etl.config.ClassificationProperties;
import com.jmip.etl.config.ClassificationProperties.Category;
import com.jmip.etl.model.JobClassification;
import com.jmip.etl.model.JobClassification.Signal;
import com.jmip.etl.model.JobClassification.SignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides what kind of role a posting is, from its title, description and skills.
 *
 * <p>Rule based and fully explainable: every category accumulates a score from the
 * signals it matched, the highest score wins, and the matched signals are kept so the
 * decision can be shown rather than asserted. There is no model and no training — the
 * same posting always classifies the same way, and changing the outcome means changing a
 * rule in configuration.
 *
 * <p>It is not expected to be right every time. Job titles are inconsistent across
 * employers, and plenty of postings genuinely span two categories; the confidence score
 * exists to say how clear-cut the call was.
 */
@Component
public class JobClassifier {

    private static final Logger log = LoggerFactory.getLogger(JobClassifier.class);

    private final ClassificationProperties properties;
    private final List<CompiledCategory> categories;

    public JobClassifier(ClassificationProperties properties) {
        this.properties = properties;
        this.categories = properties.categories().stream().map(CompiledCategory::new).toList();
        log.info("Job classifier loaded with {} categories", categories.size());
    }

    /**
     * @param title       the posting's title
     * @param description the processed description
     * @param skills      canonical skill names already extracted from the posting
     */
    public JobClassification classify(String title, String description, Set<String> skills) {
        Map<String, List<Signal>> signalsByCategory = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        String safeTitle = title == null ? "" : title;
        String safeDescription = description == null ? "" : description;
        Set<String> lowerSkills = skills == null ? Set.of()
                : skills.stream().map(skill -> skill.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());

        for (CompiledCategory category : categories) {
            List<Signal> signals = new ArrayList<>();
            double score = 0;

            // At most one title signal per category. A title names the role once, and the
            // keyword list deliberately carries synonyms — "backend engineer" and "back
            // end engineer" both match "Backend Engineer". Counting each would score the
            // same evidence twice and inflate the confidence.
            for (Map.Entry<String, Pattern> keyword : category.titlePatterns().entrySet()) {
                if (keyword.getValue().matcher(safeTitle).find()) {
                    signals.add(new Signal(SignalType.TITLE, keyword.getKey(), properties.weights().title()));
                    score += properties.weights().title();
                    break;
                }
            }
            for (Map.Entry<String, Pattern> keyword : category.descriptionPatterns().entrySet()) {
                if (keyword.getValue().matcher(safeDescription).find()) {
                    signals.add(new Signal(SignalType.DESCRIPTION, keyword.getKey(),
                            properties.weights().description()));
                    score += properties.weights().description();
                }
            }
            for (String skill : category.skills()) {
                if (lowerSkills.contains(skill.toLowerCase(Locale.ROOT))) {
                    signals.add(new Signal(SignalType.SKILL, skill, properties.weights().skill()));
                    score += properties.weights().skill();
                }
            }

            if (score > 0) {
                signalsByCategory.put(category.name(), signals);
                scores.put(category.name(), score);
            }
        }

        if (scores.isEmpty()) {
            // Nothing matched. Saying so is more honest than forcing the posting into
            // whichever category happened to score least badly.
            return JobClassification.unclassified();
        }

        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();

        String winner = ranked.get(0).getKey();
        double winnerScore = ranked.get(0).getValue();
        double runnerUpScore = ranked.size() > 1 ? ranked.get(1).getValue() : 0;

        return new JobClassification(
                winner,
                confidence(winnerScore, runnerUpScore),
                signalsByCategory.get(winner));
    }

    /**
     * Confidence combines two things, because either alone is misleading.
     *
     * <p><b>Coverage</b> is how much evidence there was, against the score configured as a
     * complete case. One keyword in a description should not read as certainty.
     *
     * <p><b>Dominance</b> is how clearly the winner beat the next category. A posting that
     * scores equally as backend and frontend is genuinely ambiguous, however much evidence
     * it carries, and its confidence should say so.
     *
     * <p>Deliberately not a probability. It does not estimate how often this category is
     * correct; it describes the strength and clarity of the evidence, nothing more.
     */
    private double confidence(double winnerScore, double runnerUpScore) {
        double coverage = Math.min(1.0, winnerScore / properties.fullEvidenceScore());
        double dominance = winnerScore / (winnerScore + runnerUpScore);
        return Math.round(coverage * dominance * 1000.0) / 10.0;
    }

    /** Keyword patterns compiled once at startup rather than per posting. */
    private record CompiledCategory(
            String name,
            Map<String, Pattern> titlePatterns,
            Map<String, Pattern> descriptionPatterns,
            List<String> skills) {

        CompiledCategory(Category category) {
            this(category.name(),
                    compile(category.titleKeywords()),
                    compile(category.descriptionKeywords()),
                    category.skills());
        }

        private static Map<String, Pattern> compile(List<String> keywords) {
            Map<String, Pattern> compiled = new LinkedHashMap<>();
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                String trimmed = keyword.trim();
                // Whole-phrase matching with flexible spacing, so "full stack" also
                // matches "full-stack" and "fullstack", while word boundaries stop a
                // keyword matching inside a longer unrelated word.
                String pattern = java.util.Arrays.stream(trimmed.split("\\s+"))
                        .map(Pattern::quote)
                        .reduce((a, b) -> a + "[\\s\\-_]*" + b)
                        .orElse(Pattern.quote(trimmed));
                compiled.put(trimmed, Pattern.compile(
                        "(?<![\\w])" + pattern + "(?![\\w])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            return compiled;
        }
    }
}
