package com.jmip.etl.load;

/** V8.1: whether records from a source may be loaded. */
@FunctionalInterface
public interface JobSourceStatus {

    /** Every source accepted; for callers that do not track sources. */
    JobSourceStatus ALL_ACTIVE = code -> true;

    boolean isActive(String sourceCode);
}
