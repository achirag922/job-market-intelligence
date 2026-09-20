package com.jmip.service.resume;

import com.jmip.testsupport.PdfFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxResumeTextExtractorTest {

    private final PdfBoxResumeTextExtractor extractor = new PdfBoxResumeTextExtractor();

    @Test
    @DisplayName("reads the text of a single page PDF")
    void readsSinglePage() throws IOException {
        byte[] pdf = PdfFixtures.pdf(List.of(List.of("Jane Developer", "Java and Spring Boot")));

        String text = extractor.extractText(pdf);

        assertThat(text).contains("Jane Developer").contains("Java and Spring Boot");
    }

    @Test
    @DisplayName("reads every page of a multi-page PDF")
    void readsAllPages() throws IOException {
        byte[] pdf = PdfFixtures.pdf(List.of(
                List.of("Page one: Java"),
                List.of("Page two: Kubernetes"),
                List.of("Page three: Terraform")));

        String text = extractor.extractText(pdf);

        // A resume that runs over one page must not be silently truncated.
        assertThat(text).contains("Java").contains("Kubernetes").contains("Terraform");
    }

    @Test
    @DisplayName("a PDF with no text layer is rejected with a useful reason")
    void rejectsPdfWithoutText() throws IOException {
        byte[] pdf = PdfFixtures.pdf(List.of(List.of()));

        assertThatThrownBy(() -> extractor.extractText(pdf))
                .isInstanceOf(ResumeTextExtractionException.class)
                // Scanned resumes are the common case here, so the message says so.
                .hasMessageContaining("Scanned or image-only");
    }

    @Test
    @DisplayName("bytes that are not a PDF are rejected")
    void rejectsNonPdf() {
        assertThatThrownBy(() -> extractor.extractText("this is plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResumeTextExtractionException.class)
                .hasMessageContaining("could not be read as a PDF");
    }

    @Test
    @DisplayName("a truncated PDF is rejected rather than half read")
    void rejectsTruncatedPdf() throws IOException {
        byte[] pdf = PdfFixtures.pdf(List.of(List.of("Java")));
        byte[] truncated = new byte[pdf.length / 2];
        System.arraycopy(pdf, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> extractor.extractText(truncated))
                .isInstanceOf(ResumeTextExtractionException.class);
    }

    @Test
    @DisplayName("empty input is rejected")
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> extractor.extractText(new byte[0]))
                .isInstanceOf(ResumeTextExtractionException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> extractor.extractText(null))
                .isInstanceOf(ResumeTextExtractionException.class);
    }

}
