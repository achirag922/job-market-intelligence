package com.jmip.service.resume;

import java.io.IOException;

/**
 * V10.1: where resume bytes live. {@link ResumeStorageService} validates and encrypts; a store
 * only keeps and removes opaque, already-sealed bytes under a generated name, so another backend
 * (an object store) can be added without touching validation, encryption or the business logic.
 *
 * <p>Selected by {@code jmip.resume.storage.type} (JMIP_RESUME_STORAGE_TYPE). Only {@code local}
 * exists today: a directory, which in the cloud is a mounted persistent volume.
 */
public interface ResumeFileStore {

    /** Writes the bytes under this generated name, replacing anything already there. */
    void write(String name, byte[] content) throws IOException;

    /** Removes the named file; a file already gone is not an error. */
    void delete(String name) throws IOException;
}
