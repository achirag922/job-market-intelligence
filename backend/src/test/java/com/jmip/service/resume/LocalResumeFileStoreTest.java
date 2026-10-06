package com.jmip.service.resume;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V10.1: the local resume file store behind the storage abstraction. */
class LocalResumeFileStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("writes, creating the directory, and deletes idempotently")
    void writesAndDeletes() throws Exception {
        LocalResumeFileStore store = new LocalResumeFileStore(root.resolve("resumes"));
        store.write("a.pdf", new byte[] {1, 2, 3});
        assertThat(Files.readAllBytes(root.resolve("resumes/a.pdf"))).containsExactly(1, 2, 3);

        store.delete("a.pdf");
        assertThat(root.resolve("resumes/a.pdf")).doesNotExist();
        assertThatCode(() -> store.delete("a.pdf")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses names that resolve outside its directory")
    void refusesTraversal() {
        LocalResumeFileStore store = new LocalResumeFileStore(root.resolve("resumes"));
        assertThatThrownBy(() -> store.write("../escape.pdf", new byte[] {1}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.delete("../../etc/passwd")).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("escape.pdf")).doesNotExist();
    }
}
