package com.jmip.repository.projection;

/** Raw count of postings in one job category. */
public record CategoryCountRow(String category, long jobCount) {
}
