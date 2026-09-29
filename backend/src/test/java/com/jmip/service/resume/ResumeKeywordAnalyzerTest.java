package com.jmip.service.resume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V8.6: posting terms, their presence in a resume, overuse, and section headings. */
class ResumeKeywordAnalyzerTest {

    private final ResumeKeywordAnalyzer analyzer = new ResumeKeywordAnalyzer();

    @Test
    @DisplayName("terms come from the title and repeated description words, without stopwords or skills")
    void terms() {
        ResumeKeywordAnalyzer.Result result = analyzer.analyze("Data Engineer",
                "We build pipelines. Our pipelines use Python and the warehouse. The warehouse is large. Python daily.",
                "Engineer maintaining pipelines.", List.of("Python"));

        assertThat(result.present()).extracting(ResumeKeywordAnalyzer.Term::term).containsExactly("pipelines", "engineer");
        assertThat(result.present().get(0).jobMentions()).isEqualTo(2);
        assertThat(result.missing()).extracting(ResumeKeywordAnalyzer.Term::term).containsExactly("warehouse", "data");
        assertThat(result.present()).extracting(ResumeKeywordAnalyzer.Term::term).doesNotContain("python", "the", "we");
        assertThat(result.overused()).isEmpty();
    }

    @Test
    @DisplayName("a term the resume repeats ten or more times is flagged, not rewarded")
    void overuse() {
        String stuffed = "agile ".repeat(12);
        ResumeKeywordAnalyzer.Result result = analyzer.analyze("Agile Coach", "Agile teams. Agile delivery.", stuffed, List.of());
        assertThat(result.overused()).extracting(ResumeKeywordAnalyzer.Term::term).containsExactly("agile");
        assertThat(result.overused().get(0).resumeMentions()).isEqualTo(12);
    }

    @Test
    @DisplayName("sections are headings on their own line; missing text means none")
    void sections() {
        assertThat(analyzer.sections("PROFILE\nI build things.\nWork Experience:\nAcme\nEducation\nBSc"))
                .containsExactly("Summary", "Experience", "Education");
        assertThat(analyzer.sections("I have experience with education software.")).isEmpty();
        assertThat(analyzer.sections(null)).isEmpty();
    }
}
