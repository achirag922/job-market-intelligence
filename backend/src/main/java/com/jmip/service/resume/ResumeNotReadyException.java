package com.jmip.service.resume;

/**
 * The resume exists but cannot be used yet: still processing, or processing failed.
 *
 * <p>Deliberately not a 404 — the resource is there — and not a 400, because the request
 * was well formed. It is a conflict with the resource's current state, which is what 409
 * means.
 */
public class ResumeNotReadyException extends RuntimeException {

    public ResumeNotReadyException(String message) {
        super(message);
    }
}
