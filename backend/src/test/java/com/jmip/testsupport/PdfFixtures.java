package com.jmip.testsupport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Builds real PDFs in memory for tests.
 *
 * <p>Real documents rather than stubbed bytes, so the tests exercise PDFBox itself. A
 * page with no lines produces a page with no text layer, which is how a scanned resume
 * behaves.
 */
public final class PdfFixtures {

    private PdfFixtures() {
    }

    /** @param pagesOfLines one list of lines per page */
    public static byte[] pdf(List<List<String>> pagesOfLines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<String> lines : pagesOfLines) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (lines.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -18);
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A single page PDF holding the given lines. */
    public static byte[] singlePage(List<String> lines) throws IOException {
        return pdf(List.of(lines));
    }
}
