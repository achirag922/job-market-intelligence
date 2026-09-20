package com.jmip.etl.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobDescriptionProcessorTest {

    private final JobDescriptionProcessor processor = new JobDescriptionProcessor();

    @Test
    @DisplayName("collapses runs of whitespace")
    void collapsesWhitespace() {
        assertThat(processor.process("Java     and      Spring   Boot"))
                .isEqualTo("Java and Spring Boot");
    }

    @Test
    @DisplayName("turns non-breaking spaces into ordinary ones")
    void normalisesNonBreakingSpace() {
        // Without this, "Spring Boot" written with a non-breaking space matches nothing.
        assertThat(processor.process("Spring Boot")).isEqualTo("Spring Boot");
    }

    @Test
    @DisplayName("strips HTML tags but keeps the text")
    void stripsHtml() {
        String result = processor.process("<p>We use <strong>Java</strong> and Spring Boot.</p>");

        assertThat(result).doesNotContain("<p>", "<strong>");
        assertThat(result).contains("Java").contains("Spring Boot");
    }

    @Test
    @DisplayName("block level tags become line breaks, so list items stay separate")
    void blockTagsBecomeLineBreaks() {
        // Without this the items run together into "Spring Boot Kafka", a phrase the
        // posting never contained.
        String result = processor.process("<li>Spring Boot</li><li>Kafka</li>");

        assertThat(result).doesNotContain("Boot Kafka");
        assertThat(result.lines().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("decodes the entities that matter for matching")
    void decodesEntities() {
        assertThat(processor.process("Spring&nbsp;Boot &amp; Kafka")).isEqualTo("Spring Boot & Kafka");
    }

    @Test
    @DisplayName("rejoins a word split across lines by a soft hyphen")
    void rejoinsHyphenatedWords() {
        assertThat(processor.process("Kuber­\nnetes")).isEqualTo("Kubernetes");
    }

    @Test
    @DisplayName("removes bullet glyphs but keeps what follows")
    void removesBullets() {
        assertThat(processor.process("• Java\n• Python")).isEqualTo("Java\nPython");
    }

    @Test
    @DisplayName("keeps technical punctuation, which carries meaning")
    void keepsTechnicalPunctuation() {
        // Stripping punctuation would turn Node.js into "Node js" and C# into "C".
        assertThat(processor.process("Node.js, C# and C++")).isEqualTo("Node.js, C# and C++");
    }

    @Test
    @DisplayName("does not change case, because matching is already case-insensitive")
    void preservesCase() {
        assertThat(processor.process("Java AWS Kubernetes")).isEqualTo("Java AWS Kubernetes");
    }

    @Test
    @DisplayName("trims runaway blank lines")
    void trimsBlankLines() {
        assertThat(processor.process("Responsibilities\n\n\n\n\nRequirements"))
                .isEqualTo("Responsibilities\n\nRequirements");
    }

    @Test
    @DisplayName("a null or blank description yields empty text rather than null")
    void handlesMissingDescription() {
        assertThat(processor.process(null)).isEmpty();
        assertThat(processor.process("")).isEmpty();
        assertThat(processor.process("   \n  ")).isEmpty();
    }

    @Test
    @DisplayName("processing twice changes nothing")
    void isIdempotent() {
        String once = processor.process("<p>Java  &amp;  Spring Boot</p>");
        assertThat(processor.process(once)).isEqualTo(once);
    }
}
