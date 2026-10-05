package com.jmip.dto.resume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * V9.4: a new built resume. Everything is optional: without content it starts from the account's name.
 *
 * @param title        the version's name, as for uploaded resumes; "Resume" when absent
 * @param versionLabel e.g. "Backend roles"
 */
public record BuilderResumeRequest(@Size(max = 100) String title, @Size(max = 50) String versionLabel,
                                   @Valid BuilderContent content) {
}
