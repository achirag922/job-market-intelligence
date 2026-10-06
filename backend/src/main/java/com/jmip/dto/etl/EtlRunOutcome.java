package com.jmip.dto.etl;

import java.util.Locale;

/** A Spring Batch status reduced to the four states a monitoring dashboard needs. */
public enum EtlRunOutcome {
    SUCCEEDED,
    FAILED,
    RUNNING,
    /** Stopped by an operator, abandoned, or a status this version does not recognise. */
    STOPPED;

    /**
     * Maps a {@code BATCH_JOB_EXECUTION.STATUS} value. The backend reads the table directly
     * rather than depending on Spring Batch, so the status arrives as text.
     */
    public static EtlRunOutcome fromBatchStatus(String status) {
        if (status == null) {
            return STOPPED;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> SUCCEEDED;
            case "FAILED" -> FAILED;
            case "STARTING", "STARTED", "STOPPING" -> RUNNING;
            default -> STOPPED;
        };
    }
}
