package com.jmip.entity;

/**
 * Lifecycle of an uploaded resume.
 *
 * <p>Extraction currently runs inside the upload request, so a resume normally reaches
 * COMPLETED or FAILED before the caller sees it. PROCESSING still matters: it is what a
 * row is left in if the application dies mid-extraction, and it is the state an
 * asynchronous pipeline would use without changing this contract.
 */
public enum ResumeProcessingStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED
}
