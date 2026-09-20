package com.jmip.service.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JobTitleNormalizerTest {

    private final JobTitleNormalizer normalizer = new JobTitleNormalizer();

    @ParameterizedTest
    @ValueSource(strings = {
            "Backend Engineer",
            "Senior Backend Engineer",
            "Junior Backend Engineer",
            "Lead Backend Engineer",
            "Principal Backend Engineer",
            "Staff Backend Engineer",
            "Intern Backend Engineer",
            "Sr. Backend Engineer",
            "Associate Backend Engineer",
            "Entry Level Backend Engineer",
    })
    @DisplayName("seniority prefixes collapse into one role")
    void seniorityPrefixesCollapse(String title) {
        assertThat(normalizer.normalize(title)).isEqualTo("Backend Engineer");
    }

    @Test
    @DisplayName("stacked seniority words are all removed")
    void stackedSeniorityWords() {
        assertThat(normalizer.normalize("Senior Lead Data Engineer")).isEqualTo("Data Engineer");
    }

    @ParameterizedTest
    @CsvSource({
            "'Backend Engineer (Remote)', Backend Engineer",
            "'Backend Engineer (m/f/d)', Backend Engineer",
            "'Data Analyst (Berlin, hybrid)', Data Analyst",
    })
    @DisplayName("parenthetical qualifiers are removed")
    void parentheticalQualifiersRemoved(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "'Software Engineer II', Software Engineer",
            "'Software Engineer III', Software Engineer",
            "'Data Analyst 2', Data Analyst",
    })
    @DisplayName("trailing level markers are removed")
    void trailingLevelsRemoved(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("matching ignores case and surplus whitespace")
    void ignoresCaseAndWhitespace() {
        assertThat(normalizer.normalize("  SENIOR   backend    engineer ")).isEqualTo("Backend Engineer");
    }

    @Test
    @DisplayName("text after a dash is kept, because it names a different role")
    void keepsTextAfterDash() {
        // Merging these would invent a trend that is not in the data.
        assertThat(normalizer.normalize("Engineer - Payments"))
                .isNotEqualTo(normalizer.normalize("Engineer - Search"));
    }

    @Test
    @DisplayName("genuinely different roles stay separate")
    void differentRolesStaySeparate() {
        assertThat(normalizer.normalize("Backend Engineer"))
                .isNotEqualTo(normalizer.normalize("Frontend Engineer"));
        assertThat(normalizer.normalize("Data Engineer"))
                .isNotEqualTo(normalizer.normalize("Data Scientist"));
    }

    @Test
    @DisplayName("a title that is only a seniority word keeps its own identity")
    void seniorityOnlyTitleSurvives() {
        // Stripping everything would fold every such posting into one empty group.
        assertThat(normalizer.normalize("Intern")).isEqualTo("Intern");
        assertThat(normalizer.normalize("Senior")).isEqualTo("Senior");
    }

    @ParameterizedTest
    @CsvSource({
            "'senior qa automation engineer', QA Automation Engineer",
            "'lead devops engineer', DevOps Engineer",
            "'senior ios developer', iOS Developer",
            "'ml engineer', ML Engineer",
    })
    @DisplayName("acronyms keep their usual form rather than being title cased")
    void acronymsPreserved(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a missing title becomes a named group rather than blank")
    void missingTitle() {
        assertThat(normalizer.normalize(null)).isEqualTo("Unknown");
        assertThat(normalizer.normalize("   ")).isEqualTo("Unknown");
    }

    @Test
    @DisplayName("normalising twice changes nothing")
    void isIdempotent() {
        String once = normalizer.normalize("Senior Backend Engineer (Remote) II");
        assertThat(normalizer.normalize(once)).isEqualTo(once);
    }
}
