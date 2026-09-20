package com.jmip.service.resume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTextNormalizerTest {

    private final ResumeTextNormalizer normalizer = new ResumeTextNormalizer();

    @Test
    @DisplayName("collapses runs of spaces left by PDF layout")
    void collapsesSpacing() {
        assertThat(normalizer.normalize("Java     and      Python")).isEqualTo("Java and Python");
    }

    @Test
    @DisplayName("turns non-breaking spaces into ordinary ones")
    void normalisesNonBreakingSpace() {
        // A PDF writing "Spring<nbsp>Boot" would otherwise never match "Spring Boot".
        assertThat(normalizer.normalize("Spring Boot")).isEqualTo("Spring Boot");
    }

    @Test
    @DisplayName("rejoins a word split across lines by a soft hyphen")
    void rejoinsSoftHyphenatedWords() {
        assertThat(normalizer.normalize("Kuber­\nnetes")).isEqualTo("Kubernetes");
    }

    @Test
    @DisplayName("removes bullet glyphs but keeps what follows them")
    void removesBullets() {
        String normalized = normalizer.normalize("• Java\n• Python");
        assertThat(normalized).isEqualTo("Java\nPython");
    }

    @Test
    @DisplayName("keeps line structure, so bullets do not run into each other")
    void keepsLineStructure() {
        // Flattening these would create the phrase "Spring Boot Kafka", which the resume
        // never contained, and could invent a two-word skill that was not written.
        String normalized = normalizer.normalize("Spring Boot\nKafka");
        assertThat(normalized).isEqualTo("Spring Boot\nKafka");
        assertThat(normalized).doesNotContain("Boot Kafka");
    }

    @Test
    @DisplayName("trims runaway blank lines to at most one")
    void trimsBlankLines() {
        assertThat(normalizer.normalize("Experience\n\n\n\n\nSkills")).isEqualTo("Experience\n\nSkills");
    }

    @Test
    @DisplayName("folds ligatures into plain letters")
    void foldsLigatures() {
        assertThat(normalizer.normalize("ﬁnance")).isEqualTo("finance");
    }

    @Test
    @DisplayName("strips leading and trailing whitespace on every line")
    void stripsPerLineWhitespace() {
        assertThat(normalizer.normalize("   Java   \n   Python   ")).isEqualTo("Java\nPython");
    }

    @Test
    @DisplayName("empty input yields empty output rather than null")
    void handlesEmptyInput() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("meaningful punctuation survives")
    void keepsMeaningfulText() {
        assertThat(normalizer.normalize("Node.js, React and C#"))
                .isEqualTo("Node.js, React and C#");
    }
}
