package com.jmip.service.resume;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Resume files in a directory: a local folder in development, a mounted volume in production.
 * Names are generated, but a name that would resolve outside the directory is refused anyway.
 *
 * <p>V10.2: on POSIX file systems (the Linux containers) new directories and files are readable
 * by the service account only. Files are encrypted before they get here; this keeps other
 * accounts on a shared host or volume from even copying them.
 */
public class LocalResumeFileStore implements ResumeFileStore {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    static final Set<PosixFilePermission> OWNER_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private final Path directory;

    public LocalResumeFileStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** Creates the directory if needed and checks that it can be written to; fails with a clear message otherwise. */
    public void verifyReady() {
        try {
            createDirectory();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the resume storage directory (JMIP_RESUME_DIR)", e);
        }
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new IllegalStateException("The resume storage directory (JMIP_RESUME_DIR) is not a writable directory");
        }
    }

    @Override
    public void write(String name, byte[] content) throws IOException {
        Path target = resolve(name);
        createDirectory();
        Files.write(target, content);
        if (POSIX) {
            Files.setPosixFilePermissions(target, OWNER_FILE);
        }
    }

    @Override
    public void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }

    private void createDirectory() throws IOException {
        if (Files.isDirectory(directory)) {
            // An existing (mounted) directory keeps the permissions it was given.
            return;
        }
        if (POSIX) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY));
        } else {
            Files.createDirectories(directory);
        }
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

    static boolean posix() {
        return POSIX;
    }
}
