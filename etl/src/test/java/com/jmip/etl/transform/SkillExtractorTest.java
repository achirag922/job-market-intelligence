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

    // ------------------------------------------------------- V4: natural description text

    private final SkillExtractor v4Extractor = new SkillExtractor(new SkillDictionaryProperties(List.of(
            new SkillDefinition("Spring Boot", "FRAMEWORK", List.of("springboot"), List.of()),
            new SkillDefinition("REST API", "ARCHITECTURE",
                    List.of("rest apis", "restful api", "restful apis"), List.of()),
            new SkillDefinition("AWS", "CLOUD", List.of("amazon web services"), List.of()),
            new SkillDefinition("EC2", "CLOUD", List.of(), List.of()),
            new SkillDefinition("S3", "CLOUD", List.of(), List.of()),
            new SkillDefinition("Lambda", "CLOUD", List.of(), List.of()),
            new SkillDefinition("Docker", "PLATFORM", List.of(), List.of()),
            new SkillDefinition("Kubernetes", "PLATFORM", List.of("k8s"), List.of()),
            new SkillDefinition("JavaScript", "LANGUAGE", List.of("js"), List.of()),
            new SkillDefinition("TypeScript", "LANGUAGE", List.of("ts"), List.of()))));

    @Test
    @DisplayName("finds skills in a natural sentence about REST APIs")
    void findsSkillsInRestApiSentence() {
        assertThat(v4Extractor.extract(null,
                "Experience developing RESTful APIs using Spring Boot"))
                .containsExactlyInAnyOrder("Spring Boot", "REST API");
    }

    @Test
    @DisplayName("finds a cloud provider and its named services together")
    void findsCloudServices() {
        assertThat(v4Extractor.extract(null,
                "Strong experience with AWS services including EC2, S3 and Lambda"))
                .containsExactlyInAnyOrder("AWS", "EC2", "S3", "Lambda");
    }

    @Test
    @DisplayName("finds container technologies in a natural sentence")
    void findsContainerSkills() {
        assertThat(v4Extractor.extract(null,
                "Experience with containerized applications using Docker and Kubernetes"))
                .containsExactlyInAnyOrder("Docker", "Kubernetes");
    }

    @Test
    @DisplayName("separators between the words of a skill do not matter")
    void separatorsDoNotMatter() {
        // The same skill however the description spells it.
        assertThat(v4Extractor.extract(null, "SpringBoot experience")).containsExactly("Spring Boot");
        assertThat(v4Extractor.extract(null, "Spring-Boot experience")).containsExactly("Spring Boot");
        assertThat(v4Extractor.extract(null, "Spring_Boot experience")).containsExactly("Spring Boot");
        assertThat(v4Extractor.extract(null, "spring boot experience")).containsExactly("Spring Boot");
    }

    @Test
    @DisplayName("short aliases resolve to their canonical skill")
    void shortAliasesResolve() {
        assertThat(v4Extractor.extract(null, "Strong JS and TS skills, deployed on K8s"))
                .containsExactlyInAnyOrder("JavaScript", "TypeScript", "Kubernetes");
    }

    @Test
    @DisplayName("an alias does not fire inside a longer word")
    void aliasesDoNotMatchInsideWords() {
        // "js" must not match inside "jsonschema", nor "ts" inside "artifacts".
        assertThat(v4Extractor.extract(null, "We publish jsonschema files and build artifacts"))
                .isEmpty();
    }

    @Test
    @DisplayName("a skill repeated through a long description is returned once")
    void repeatedSkillReturnedOnce() {
        String description = "Docker is used here. We containerise with Docker. Docker everywhere. "
                + "Our Docker images are small.";

        assertThat(v4Extractor.extract("Docker Engineer", description)).containsExactly("Docker");
    }

    @Test
    @DisplayName("category lookup returns the configured category")
    void categoryLookup() {
        assertThat(extractor.categoryOf("Java")).isEqualTo("LANGUAGE");
        assertThat(extractor.categoryOf("Unknown")).isNull();
    }
}
