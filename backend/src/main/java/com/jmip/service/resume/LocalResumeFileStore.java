package com.jmip.service.resume;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resume files in a directory: a local folder in development, a mounted volume in production.
 * Names are generated, but a name that would resolve outside the directory is refused anyway.
 */
public class LocalResumeFileStore implements ResumeFileStore {

    private final Path directory;

    public LocalResumeFileStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public void write(String name, byte[] content) throws IOException {
        Path target = resolve(name);
        Files.createDirectories(directory);
        Files.write(target, content);
    }

    @Override
    public void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }

    private Path resolve(String name) {
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(directory) || target.equals(directory)) {
            throw new IllegalStateException("Refusing to use a path outside the resume storage directory");
        }
        return target;
    }

    Path directory() {
        return directory;
    }
}
