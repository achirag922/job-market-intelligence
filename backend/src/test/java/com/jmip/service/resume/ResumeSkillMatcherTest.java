package com.jmip.service.resume;

import com.jmip.entity.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeSkillMatcherTest {

    private static final List<Skill> VOCABULARY = List.of(
            skill(1L, "Java", "LANGUAGE"),
            skill(2L, "Python", "LANGUAGE"),
            skill(3L, "SQL", "LANGUAGE"),
            skill(4L, "Spring Boot", "FRAMEWORK"),
            skill(5L, "Kubernetes", "PLATFORM"),
            skill(6L, "Git", "TOOL"),
            skill(7L, "React", "FRONTEND"),
            skill(8L, "Node.js", "FRONTEND"));

    private final ResumeSkillMatcher matcher = new ResumeSkillMatcher(null);

    @Test
    @DisplayName("finds skills in ordinary resume prose")
    void findsSkillsInProse() {
        Set<Skill> found = matcher.match(
                "Built services in Java and Python, backed by SQL databases.", VOCABULARY);

        assertThat(names(found)).containsExactlyInAnyOrder("Java", "Python", "SQL");
    }

    @Test
    @DisplayName("matching ignores case")
    void ignoresCase() {
        assertThat(names(matcher.match("JAVA, python and sql", VOCABULARY)))
                .containsExactlyInAnyOrder("Java", "Python", "SQL");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Spring Boot", "spring boot", "SPRING BOOT", "springboot", "Spring  Boot", "Spring-Boot",
    })
    @DisplayName("spacing and punctuation do not change what a skill is")
    void normalisesSpacingAndPunctuation(String written) {
        // The example from the specification: "Spring Boot" and "springboot" are one skill.
        assertThat(names(matcher.match("Experience with " + written + " in production", VOCABULARY)))
                .contains("Spring Boot");
    }

    @Test
    @DisplayName("a trailing js is recognised as the same skill")
    void recognisesJsSuffix() {
        assertThat(names(matcher.match("Frontend work in React.js", VOCABULARY))).contains("React");
        assertThat(names(matcher.match("Backend in Node.js", VOCABULARY))).contains("Node.js");
        assertThat(names(matcher.match("Backend in nodejs", VOCABULARY))).contains("Node.js");
    }

    @Test
    @DisplayName("Java is not matched inside JavaScript")
    void javaNotMatchedInsideJavaScript() {
        assertThat(names(matcher.match("Strong JavaScript developer", VOCABULARY)))
                .doesNotContain("Java");
    }

    @Test
    @DisplayName("SQL is not matched inside PostgreSQL or NoSQL")
    void sqlNotMatchedInsideOtherWords() {
        assertThat(names(matcher.match("Used PostgreSQL and NoSQL stores", VOCABULARY)))
                .doesNotContain("SQL");
    }

    @Test
    @DisplayName("Git is not matched inside GitHub")
    void gitNotMatchedInsideGitHub() {
        assertThat(names(matcher.match("Managed releases on GitHub", VOCABULARY)))
                .doesNotContain("Git");
    }

    @Test
    @DisplayName("a skill mentioned repeatedly is returned once")
    void duplicatesAreCollapsed() {
        Set<Skill> found = matcher.match(
                "Java developer. Java, Java and more Java. JAVA.", VOCABULARY);

        assertThat(found).hasSize(1);
        assertThat(names(found)).containsExactly("Java");
    }

    @Test
    @DisplayName("punctuation around a skill does not hide it")
    void punctuationDoesNotHideSkills() {
        assertThat(names(matcher.match("Skills: Java, Python; SQL. (Kubernetes)", VOCABULARY)))
                .containsExactlyInAnyOrder("Java", "Python", "SQL", "Kubernetes");
    }

    @Test
    @DisplayName("a skill split across a line break is still found")
    void matchesAcrossLineBreaks() {
        assertThat(names(matcher.match("Frameworks:\nSpring Boot\nKubernetes", VOCABULARY)))
                .contains("Spring Boot", "Kubernetes");
    }

    @Test
    @DisplayName("empty or unknown text yields no skills")
    void emptyTextYieldsNothing() {
        assertThat(matcher.match(null, VOCABULARY)).isEmpty();
        assertThat(matcher.match("   ", VOCABULARY)).isEmpty();
        assertThat(matcher.match("Enthusiastic team player with strong communication", VOCABULARY))
                .isEmpty();
    }

    @Test
    @DisplayName("an empty vocabulary yields no skills rather than failing")
    void emptyVocabulary() {
        assertThat(matcher.match("Java and Python", List.of())).isEmpty();
    }

    private static List<String> names(Set<Skill> skills) {
        return skills.stream().map(Skill::getName).toList();
    }

    /** Entities have no public constructor, so test fixtures are built reflectively. */
    private static Skill skill(Long id, String name, String category) {
        try {
            Constructor<Skill> constructor = Skill.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Skill skill = constructor.newInstance();
            set(skill, "id", id);
            set(skill, "name", name);
            set(skill, "category", category);
            return skill;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build a Skill fixture", e);
        }
    }

    private static void set(Skill skill, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = Skill.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(skill, value);
    }
}
