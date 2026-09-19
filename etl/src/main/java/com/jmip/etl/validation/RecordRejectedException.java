package com.jmip.etl.validation;

import java.util.List;

/**
 * Signals that a record cannot be loaded. Spring Batch is configured to skip this
 * exception, and a listener records the offending input so nothing is lost silently.
 */
public class RecordRejectedException extends RuntimeException {

    private final transient List<String> reasons;

    public RecordRejectedException(List<String> reasons) {
        super(String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
