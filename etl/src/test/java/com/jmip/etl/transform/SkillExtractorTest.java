package com.jmip.etl.transform;

import com.jmip.etl.config.SkillDictionaryProperties;
import com.jmip.etl.config.SkillDictionaryProperties.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillExtractorTest {

    private final SkillExtractor extractor = new SkillExtractor(new SkillDictionaryProperties(List.of(
            new SkillDefinition("Java", "LANGUAGE", List.of("core java"), List.of()),
            new SkillDefinition("Python", "LANGUAGE", List.of(), List.of()),
            new SkillDefinition("SQL", "LANGUAGE", List.of(), List.of()),
            new SkillDefinition("Spring", "FRAMEWORK", List.of(), List.of("Spring Boot")),
            new SkillDefinition("Spring Boot", "FRAMEWORK", List.of("springboot"), List.of()),
            new SkillDefinition("Kubernetes", "PLATFORM", List.of("k8s"), List.of()),
            new SkillDefinition("Node.js", "FRONTEND", List.of("nodejs"), List.of()),
            new SkillDefinition("Git", "TOOL", List.of(), List.of()),
            new SkillDefinition("React", "FRONTEND", List.of(), List.of()))));

    @Test
    @DisplayName("finds skills in the description")
    void findsSkillsInDescription() {
        assertThat(extractor.extract(null, "We use Java and Python daily."))
                .containsExactlyInAnyOrder("Java", "Python");
    }

    @Test
    @DisplayName("finds skills in the title as well as the description")
    void findsSkillsInTitle() {
        assertThat(extractor.extract("Senior React Engineer", "Build things."))
                .containsExactly("React");
    }

    @Test
    @DisplayName("matching ignores case")
    void matchingIgnoresCase() {
        assertThat(extractor.extract(null, "experience with JAVA, kubernetes and sql"))
                .containsExactlyInAnyOrder("Java", "Kubernetes", "SQL");
    }

    @Test
    @DisplayName("aliases resolve to the canonical name")
    void aliasesResolveToCanonicalName() {
        assertThat(extractor.extract(null, "Strong k8s and core java background, plus nodejs."))
                .containsExactlyInAnyOrder("Kubernetes", "Java", "Node.js");
    }

    @Test
    @DisplayName("Java is not matched inside JavaScript")
    void javaIsNotMatchedInsideJavaScript() {
        assertThat(extractor.extract(null, "Strong JavaScript skills required.")).isEmpty();
    }

    @Test
    @DisplayName("SQL is not matched inside PostgreSQL, MySQL or NoSQL")
    void sqlIsNotMatchedInsideOtherWords() {
        assertThat(extractor.extract(null, "PostgreSQL, MySQL and NoSQL experience.")).isEmpty();
    }

    @Test
    @DisplayName("Git is not matched inside GitHub or GitOps")
    void gitIsNotMatchedInsideOtherWords() {
        assertThat(extractor.extract(null, "We use GitHub Actions and GitOps.")).isEmpty();
    }

    @Test
    @DisplayName("a more specific skill suppresses the one it supersedes")
    void moreSpecificSkillSuppressesGeneralOne() {
        assertThat(extractor.extract(null, "Built with Spring Boot."))
                .containsExactly("Spring Boot")
                .doesNotContain("Spring");
    }

    @Test
    @DisplayName("both are kept when the general skill is mentioned in its own right")
    void keepsBothWhenGeneralSkillStandsAlone() {
        assertThat(extractor.extract(null, "Spring and Spring Boot are both used here."))
                .containsExactly("Spring Boot");
    }

    @Test
    @DisplayName("punctuated names such as Node.js match exactly")
    void punctuatedNamesMatch() {
        assertThat(extractor.extract(null, "Node.js on the server.")).containsExactly("Node.js");
    }

    @Test
    @DisplayName("empty input yields no skills")
    void emptyInputYieldsNoSkills() {
        assertThat(extractor.extract(null, null)).isEmpty();
        assertThat(extractor.extract("", "")).isEmpty();
    }

    @Test
    @DisplayName("category lookup returns the configured category")
    void categoryLookup() {
        assertThat(extractor.categoryOf("Java")).isEqualTo("LANGUAGE");
        assertThat(extractor.categoryOf("Unknown")).isNull();
    }
}
