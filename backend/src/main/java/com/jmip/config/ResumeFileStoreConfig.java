package com.jmip.config;

import com.jmip.service.resume.LocalResumeFileStore;
import com.jmip.service.resume.ResumeFileStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Locale;

/**
 * V10.1: picks the resume file store from JMIP_RESUME_STORAGE_TYPE. An unknown type stops the
 * start-up with a clear message instead of silently falling back to the local disk.
 */
@Configuration
public class ResumeFileStoreConfig {

    @Bean
    ResumeFileStore resumeFileStore(ResumeStorageProperties properties) {
        ResumeFileStore store = create(properties);
        // V10.2: an unwritable or missing volume fails the start, not the first upload.
        if (store instanceof LocalResumeFileStore local) {
            local.verifyReady();
        }
        return store;
    }

    static ResumeFileStore create(ResumeStorageProperties properties) {
        String type = properties.type().strip().toLowerCase(Locale.ROOT);
        if (type.equals("local")) {
            return new LocalResumeFileStore(Path.of(properties.directory()));
        }
        throw new IllegalStateException(
                "Unknown JMIP_RESUME_STORAGE_TYPE '" + properties.type() + "'; supported: local");
    }
}
