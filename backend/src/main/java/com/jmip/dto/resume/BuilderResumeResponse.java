package com.jmip.dto.resume;

/** V9.4: a built resume: its version metadata, as every resume has, and its sections. */
public record BuilderResumeResponse(ResumeResponse resume, BuilderContent content) {
}
