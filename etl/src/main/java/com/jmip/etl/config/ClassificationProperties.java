package com.jmip.etl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The classification rules, held in configuration rather than in Java.
 *
 * <p>These are long keyword lists that will change far more often than the scoring code,
 * and scattering them through classes would make adding a category a code change. Adding
 * one here is a configuration change.
 *
 * @param weights          how much each kind of evidence counts
 * @param fullEvidenceScore the score at which the evidence is considered complete; scores
 *                          above it do not raise confidence further
 * @param categories       the controlled vocabulary of categories and their signals
 */
@ConfigurationProperties(prefix = "jmip.etl.classification")
public record ClassificationProperties(
        @DefaultValue Weights weights,
        @DefaultValue("10") double fullEvidenceScore,
        List<Category> categories) {

    /** The category assigned when nothing matches. Never inferred, always explicit. */
    public static final String UNCLASSIFIED_CATEGORY = "Other";

    public ClassificationProperties {
        categories = categories == null ? List.of() : List.copyOf(categories);
        if (fullEvidenceScore <= 0) {
            fullEvidenceScore = 10;
        }
    }

    /**
     * Title evidence outweighs the rest because an employer naming the role is stating it
     * directly. A skill is supporting evidence: Java appears in plenty of jobs that are
     * not Java roles. A description keyword is the weakest, being the easiest to mention
     * in passing.
     */
    public record Weights(
            @DefaultValue("5") double title,
            @DefaultValue("2") double skill,
            @DefaultValue("1") double description) {
    }

    /**
     * @param name                the canonical category name
     * @param titleKeywords       phrases in the job title that identify this category
     * @param descriptionKeywords phrases in the description that suggest it
     * @param skills              skills whose presence supports it
     */
    public record Category(
            String name,
            List<String> titleKeywords,
            List<String> descriptionKeywords,
            List<String> skills) {

        public Category {
            titleKeywords = titleKeywords == null ? List.of() : List.copyOf(titleKeywords);
            descriptionKeywords = descriptionKeywords == null ? List.of() : List.copyOf(descriptionKeywords);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }
}
