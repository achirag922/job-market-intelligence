package com.jmip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Where uploaded resumes are kept, and what is accepted.
 *
 * @param directory       where files are written. Relative by default, so nothing in the
 *                        build assumes a particular machine's filesystem layout; override
 *                        it per environment
 * @param maxFileSize     largest accepted upload. The same value backs the servlet
 *                        multipart limit in application.yml, so the container and the
 *                        service agree rather than rejecting at two different sizes
 * @param allowedContentTypes content types accepted on upload
 */
@ConfigurationProperties(prefix = "jmip.resume.storage")
public record ResumeStorageProperties(
        @DefaultValue("./data/resumes") String directory,
        @DefaultValue("5MB") DataSize maxFileSize,
        @DefaultValue("application/pdf") List<String> allowedContentTypes) {

    public ResumeStorageProperties {
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? List.of("application/pdf")
                : List.copyOf(allowedContentTypes);
    }

    public long maxFileSizeBytes() {
        return maxFileSize.toBytes();
    }
}
