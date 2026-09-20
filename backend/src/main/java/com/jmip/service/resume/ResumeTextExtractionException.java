package com.jmip.service.resume;

/**
 * The document could not be read. Distinct from an invalid upload: the file arrived
 * intact and was accepted, but nothing useful could be got out of it.
 */
public class ResumeTextExtractionException extends RuntimeException {

    public ResumeTextExtractionException(String message) {
        super(message);
    }

    public ResumeTextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
