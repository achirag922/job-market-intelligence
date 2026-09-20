package com.jmip.service.resume;

/**
 * Pulls readable text out of an uploaded document.
 *
 * <p>An interface with one implementation, which is usually worth avoiding. It earns its
 * place here because the extraction technology is the part of this feature most likely to
 * be swapped: for OCR over scanned resumes, or for formats other than PDF. Everything
 * downstream depends on this contract rather than on PDFBox.
 */
public interface ResumeTextExtractor {

    /**
     * @param content the raw uploaded bytes
     * @return the document's text, not yet normalised
     * @throws ResumeTextExtractionException if the document cannot be read, or holds no
     *                                       extractable text at all
     */
    String extractText(byte[] content);
}
