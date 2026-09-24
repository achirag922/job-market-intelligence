package com.jmip.service.resume;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Reads PDF text with Apache PDFBox.
 *
 * <p>Pages are read in order and concatenated, so a resume that runs to several pages is
 * extracted whole.
 */
@Component
public class PdfBoxResumeTextExtractor implements ResumeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxResumeTextExtractor.class);

    static final int MAX_PAGES = 30;
    private static final long MAX_WORKING_MEMORY_BYTES = 64L * 1024 * 1024;

    @Override
    public String extractText(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ResumeTextExtractionException("The uploaded file is empty");
        }

        // Bounded working memory and pages: a small, deliberately crafted PDF can expand to far
        // more than its size on disk, and no resume needs more than a few pages.
        try (PDDocument document = Loader.loadPDF(content, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(MAX_WORKING_MEMORY_BYTES).streamCache)) {
            if (document.isEncrypted()) {
                // PDFBox can open some encrypted documents but not extract from them;
                // saying so is more useful than returning nothing.
                throw new ResumeTextExtractionException(
                        "The PDF is password protected and its text cannot be read");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(MAX_PAGES);
            String text = stripper.getText(document);

            log.debug("Extracted {} characters from a {} page PDF", text.length(), document.getNumberOfPages());

            if (text.isBlank()) {
                // Almost always a scanned resume: pages of images with no text layer.
                // Reading it would need OCR, which this deliberately does not do.
                throw new ResumeTextExtractionException(
                        "No text could be read from the PDF. Scanned or image-only resumes are not supported");
            }
            return text;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResumeTextExtractionException known) {
                throw known;
            }
            // A malformed document can make the parser throw unchecked exceptions too; either
            // way it is a file that could not be read, not a server fault.
            throw new ResumeTextExtractionException("The file could not be read as a PDF", e);
        }
    }
}
