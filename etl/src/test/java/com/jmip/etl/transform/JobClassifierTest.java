package com.jmip.etl.transform;

import com.jmip.etl.config.ClassificationProperties;
import com.jmip.etl.config.ClassificationProperties.Category;
import com.jmip.etl.config.ClassificationProperties.Weights;
import com.jmip.etl.model.JobClassification;
import com.jmip.etl.model.JobClassification.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JobClassifierTest {

    /** A small, hand-checkable subset of the real rules. */
    private final JobClassifier classifier = new JobClassifier(new ClassificationProperties(
            new Weights(5, 2, 1),
            10,
            List.of(
                    new Category("Backend Developer",
                            List.of("backend engineer", "backend developer", "back end engineer"),
                            List.of("microservices architecture", "rest apis"),
                            List.of("Java", "Spring Boot", "Kafka", "Microservices")),
                    new Category("Frontend Developer",
                            List.of("frontend engineer", "ui engineer"),
                            List.of("user interface", "single page application"),
                            List.of("React", "JavaScript", "TypeScript", "CSS")),
                    new Category("Data Engineer",
                            List.of("data engineer"),
                            List.of("data pipeline", "data warehouse"),
                            List.of("Spark", "Databricks", "SQL", "Python", "Airflow")),
                    new Category("DevOps Engineer",
                            List.of("devops engineer", "site reliability engineer"),
                            List.of("ci cd", "infrastructure as code"),
                            List.of("Kubernetes", "Terraform", "Docker", "Jenkins")),
                    new Category("QA / Automation Engineer",
                            List.of("qa engineer", "automation engineer", "sdet"),
                            List.of("test automation", "regression coverage"),
                            List.of("Selenium", "TestNG", "JUnit")))));

    @Test
    @DisplayName("a Java backend job is classified as Backend Developer")
    void classifiesBackendJob() {
        JobClassification result = classifier.classify(
                "Senior Backend Engineer",
                "Build microservices architecture and REST APIs for our platform.",
                Set.of("Java", "Spring Boot", "Kafka"));

        assertThat(result.category()).isEqualTo("Backend Developer");
        assertThat(result.confidence()).isGreaterThan(50);
    }

    @Test
    @DisplayName("a frontend job is classified as Frontend Developer")
    void classifiesFrontendJob() {
        JobClassification result = classifier.classify(
                "Frontend Engineer",
                "Build the user interface as a single page application.",
                Set.of("React", "JavaScript", "TypeScript"));

        assertThat(result.category()).isEqualTo("Frontend Developer");
    }

    @Test
    @DisplayName("a data engineering job is classified as Data Engineer")
    void classifiesDataEngineeringJob() {
        JobClassification result = classifier.classify(
                "Data Engineer",
                "Own the data pipeline feeding our data warehouse.",
                Set.of("Python", "SQL", "Spark", "Databricks"));

        assertThat(result.category()).isEqualTo("Data Engineer");
    }

    @Test
    @DisplayName("a DevOps job is classified as DevOps Engineer")
    void classifiesDevOpsJob() {
        JobClassification result = classifier.classify(
                "Site Reliability Engineer",
                "Own the CI CD pipeline and infrastructure as code.",
                Set.of("Kubernetes", "Terraform", "Docker"));

        assertThat(result.category()).isEqualTo("DevOps Engineer");
    }

    @Test
    @DisplayName("a QA automation job is classified as QA / Automation Engineer")
    void classifiesQaJob() {
        JobClassification result = classifier.classify(
                "QA Automation Engineer",
                "Grow our test automation and regression coverage.",
                Set.of("Selenium", "TestNG"));

        assertThat(result.category()).isEqualTo("QA / Automation Engineer");
    }

    @Test
    @DisplayName("skills alone can classify a job whose title says nothing")
    void classifiesFromSkillsAlone() {
        JobClassification result = classifier.classify(
                "Engineer",
                "Join our team.",
                Set.of("Kubernetes", "Terraform", "Docker", "Jenkins"));

        assertThat(result.category()).isEqualTo("DevOps Engineer");
        assertThat(result.signals()).allMatch(signal -> signal.type() == SignalType.SKILL);
    }

    @Test
    @DisplayName("the title outweighs a handful of skills from another category")
    void titleOutweighsSkills() {
        // A backend role that mentions Docker and Kubernetes in passing is still a
        // backend role, which is why title carries the most weight.
        JobClassification result = classifier.classify(
                "Backend Engineer",
                "You will deploy your own services.",
                Set.of("Java", "Docker"));

        assertThat(result.category()).isEqualTo("Backend Developer");
    }

    @Test
    @DisplayName("an ambiguous job still picks one category but reports low confidence")
    void ambiguousJobHasLowConfidence() {
        // Equal evidence for backend and frontend: the category is a coin toss and the
        // confidence must say so rather than sounding certain.
        JobClassification balanced = classifier.classify(
                "Engineer",
                "",
                Set.of("Java", "React"));

        assertThat(balanced.category()).isNotEqualTo("Other");
        assertThat(balanced.confidence()).isLessThan(30);
    }

    @Test
    @DisplayName("a job with no recognisable signals is Other, with zero confidence")
    void unknownJobIsOther() {
        JobClassification result = classifier.classify(
                "Office Manager",
                "Keep the office running smoothly.",
                Set.of());

        assertThat(result.category()).isEqualTo("Other");
        assertThat(result.confidence()).isZero();
        assertThat(result.signals()).isEmpty();
    }

    @Test
    @DisplayName("null title and description are handled rather than thrown on")
    void handlesNullInput() {
        JobClassification result = classifier.classify(null, null, null);

        assertThat(result.category()).isEqualTo("Other");
        assertThat(result.confidence()).isZero();
    }

    @Test
    @DisplayName("every matched signal is reported, so the decision can be explained")
    void reportsMatchedSignals() {
        JobClassification result = classifier.classify(
                "Backend Developer",
                "Design REST APIs.",
                Set.of("Java", "Spring Boot"));

        assertThat(result.signals()).extracting(JobClassification.Signal::value)
                .contains("backend developer", "rest apis", "Java", "Spring Boot");
        assertThat(result.signals()).extracting(JobClassification.Signal::type)
                .contains(SignalType.TITLE, SignalType.DESCRIPTION, SignalType.SKILL);
    }

    @Test
    @DisplayName("confidence rises with more evidence")
    void confidenceRisesWithEvidence() {
        double weak = classifier.classify("Engineer", "", Set.of("Java")).confidence();
        double strong = classifier.classify(
                "Backend Engineer",
                "Microservices architecture and REST APIs.",
                Set.of("Java", "Spring Boot", "Kafka")).confidence();

        assertThat(strong).isGreaterThan(weak);
    }

    @Test
    @DisplayName("confidence never exceeds 100 however much evidence there is")
    void confidenceIsBounded() {
        JobClassification result = classifier.classify(
                "Backend Engineer Backend Developer",
                "Microservices architecture and REST APIs everywhere.",
                Set.of("Java", "Spring Boot", "Kafka", "Microservices"));

        assertThat(result.confidence()).isBetween(0.0, 100.0);
    }

    @Test
    @DisplayName("classification is deterministic")
    void isDeterministic() {
        JobClassification first = classifier.classify(
                "Data Engineer", "Data pipeline work.", Set.of("Spark", "SQL"));
        JobClassification second = classifier.classify(
                "Data Engineer", "Data pipeline work.", Set.of("Spark", "SQL"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("overlapping title synonyms count once, not once each")
    void titleSynonymsCountOnce() {
        // "Backend Engineer" matches both "backend engineer" and "back end engineer",
        // because the separator between words is flexible. Scoring both would count the
        // same evidence twice and inflate the confidence.
        JobClassification result = classifier.classify("Backend Engineer", "", Set.of());

        assertThat(result.signals())
                .filteredOn(signal -> signal.type() == SignalType.TITLE)
                .hasSize(1);
    }

    @Test
    @DisplayName("a keyword written with spaces also matches hyphenated text")
    void titleKeywordsToleratePunctuation() {
        // The keyword "back end engineer" matches "Back-End Engineer", because the
        // separator between its words is flexible.
        assertThat(classifier.classify("Back-End Engineer", "", Set.of()).category())
                .isEqualTo("Backend Developer");
    }
}
