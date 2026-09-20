package com.jmip.repository.projection;

/** Raw count of postings sharing one stored (not yet normalised) title. */
public record TitleCountRow(String title, long jobCount) {
}
