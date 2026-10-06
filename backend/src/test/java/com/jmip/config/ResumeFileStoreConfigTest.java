package com.jmip.config;

import com.jmip.service.resume.LocalResumeFileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V10.1: the storage type picks the store, and an unknown type stops the start-up. */
class ResumeFileStoreConfigTest {

    private static ResumeStorageProperties properties(String type) {
        return new ResumeStorageProperties("./data/resumes", DataSize.ofMegabytes(5), List.of("application/pdf"), type);
    }

    @Test
    @DisplayName("local (any case) uses the directory store")
    void localStore() {
        assertThat(ResumeFileStoreConfig.create(properties("local"))).isInstanceOf(LocalResumeFileStore.class);
        assertThat(ResumeFileStoreConfig.create(properties(" LOCAL "))).isInstanceOf(LocalResumeFileStore.class);
    }

    @Test
    @DisplayName("an unknown type fails with a clear message instead of falling back")
    void unknownType() {
        assertThatThrownBy(() -> ResumeFileStoreConfig.create(properties("s3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JMIP_RESUME_STORAGE_TYPE").hasMessageContaining("supported: local");
    }
}
